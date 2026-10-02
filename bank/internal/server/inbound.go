package server

import (
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/iso"
	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/ledger"
)

const maxMessage = 64 << 10

// inboundCredit receives a pacs.008 from the switch, credits the customer and answers with a pacs.002.
// Replays of the same end-to-end id return the same answer without crediting twice.
func (s *Server) inboundCredit(w http.ResponseWriter, r *http.Request) {
	chaos := s.currentChaos()
	if chaos.Offline {
		problem(w, http.StatusServiceUnavailable, "Bank is offline")
		return
	}
	if chaos.DelayMs > 0 {
		time.Sleep(time.Duration(chaos.DelayMs) * time.Millisecond)
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, maxMessage))
	if err != nil {
		problem(w, http.StatusBadRequest, "Unreadable body")
		return
	}
	var msg iso.Pacs008
	if err := iso.Unmarshal(body, &msg); err != nil {
		problem(w, http.StatusBadRequest, "Not a pacs.008.001.08 document: "+err.Error())
		return
	}

	reply := func(reason, info string) {
		out, _ := iso.Marshal(iso.StatusFor(s.BIC, &msg, reason, info))
		writeXML(w, http.StatusOK, out)
	}

	if err := msg.Validate(); err != nil {
		reply(iso.ReasonInvalidFormat, err.Error())
		return
	}
	if !strings.EqualFold(msg.Tx.CreditorAgent.BIC, s.BIC) {
		reply(iso.ReasonUnknownBank, "creditor agent is not this bank")
		return
	}
	if chaos.RejectAll {
		reply(iso.ReasonClosedAccount, "rejected by chaos settings")
		return
	}

	amount, _ := msg.Sen()
	e2e := msg.Tx.EndToEndID
	applied, err := s.Ledger.Credit(e2e, msg.Tx.CreditorAcct.ID, amount, e2e)
	switch {
	case errors.Is(err, ledger.ErrNoAccount):
		reply(iso.ReasonIncorrectAccount, "no such account")
		return
	case errors.Is(err, ledger.ErrClosedAccount):
		reply(iso.ReasonClosedAccount, "account closed")
		return
	case err != nil:
		problem(w, http.StatusInternalServerError, err.Error())
		return
	}
	if applied {
		s.record(&Transfer{EndToEndID: e2e, Direction: "IN", Account: msg.Tx.CreditorAcct.ID, Counterparty: msg.Tx.Debtor.Name,
			CounterBIC: msg.Tx.DebtorAgent.BIC, Amount: amount, Reference: msg.Tx.RemittanceInfo, Status: "COMPLETED", At: time.Now()})
	}
	reply("", "")
}

// cancellation handles a camt.056 from the switch: the switch gave up waiting for our answer and rejected the
// transfer to the sender, so a credit we may have applied must be reversed. Answers with a camt.029.
func (s *Server) cancellation(w http.ResponseWriter, r *http.Request) {
	if s.currentChaos().Offline {
		problem(w, http.StatusServiceUnavailable, "Bank is offline")
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxMessage))
	if err != nil {
		problem(w, http.StatusBadRequest, "Unreadable body")
		return
	}
	var req iso.Camt056
	if err := iso.Unmarshal(body, &req); err != nil || req.Tx.OriginalEndToEndID == "" {
		problem(w, http.StatusBadRequest, "Not a camt.056.001.08 document")
		return
	}
	e2e := req.Tx.OriginalEndToEndID
	reversed, err := s.Ledger.ReverseCredit(e2e, e2e)
	if err != nil {
		problem(w, http.StatusInternalServerError, err.Error())
		return
	}
	var res iso.Camt029
	if reversed {
		s.update(e2e, func(t *Transfer) { t.Status = "REVERSED"; t.Reason = req.Tx.ReasonCode })
		res = iso.ResolutionFor(s.BIC, &req, iso.CancelDone, "")
		s.Log.Info("credit reversed after switch timeout", "e2e", e2e)
	} else {
		res = iso.ResolutionFor(s.BIC, &req, iso.CancelRejected, iso.ReasonNoOriginal)
	}
	out, _ := iso.Marshal(res)
	writeXML(w, http.StatusOK, out)
}
