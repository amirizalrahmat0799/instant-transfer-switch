package server

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/iso"
	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/ledger"
	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/switchclient"
)

type proxyRequest struct {
	Type          string `json:"type"`
	Value         string `json:"value"`
	AccountNumber string `json:"accountNumber"`
}

func (s *Server) registerProxy(w http.ResponseWriter, r *http.Request) {
	var req proxyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Type == "" || req.Value == "" || req.AccountNumber == "" {
		problem(w, http.StatusBadRequest, "Body must be {type, value, accountNumber}")
		return
	}
	acct, err := s.Ledger.Get(req.AccountNumber)
	if err != nil {
		problem(w, http.StatusNotFound, "Account not found")
		return
	}
	err = s.Switch.RegisterProxy(r.Context(), switchclient.ProxyRegistration{
		Type: req.Type, Value: req.Value, AccountNumber: acct.Number, AccountName: acct.Name,
	})
	if err != nil {
		problem(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"type": req.Type, "value": req.Value, "accountNumber": acct.Number})
}

func (s *Server) lookup(w http.ResponseWriter, r *http.Request) {
	res, err := s.Switch.Lookup(r.Context(), r.PathValue("type"), r.PathValue("value"))
	if errors.Is(err, switchclient.ErrNotFound) {
		problem(w, http.StatusNotFound, "No account is registered to that ID")
		return
	}
	if err != nil {
		problem(w, http.StatusBadGateway, err.Error())
		return
	}
	// Customers only see the masked name, like a banking app's "confirm recipient" screen.
	writeJSON(w, http.StatusOK, map[string]string{"bank": res.BankName, "name": res.MaskedName})
}

type transferRequest struct {
	FromAccount string `json:"fromAccount"`
	// Either a proxy (DuitNow-style ID) ...
	ProxyType  string `json:"proxyType,omitempty"`
	ProxyValue string `json:"proxyValue,omitempty"`
	// ... or a bank and account number
	ToBIC     string `json:"toBic,omitempty"`
	ToAccount string `json:"toAccount,omitempty"`
	ToName    string `json:"toName,omitempty"`

	Amount    string `json:"amount"` // ringgit, e.g. "150.00"
	Reference string `json:"reference,omitempty"`
}

type transferResponse struct {
	EndToEndID string `json:"endToEndId"`
	Status     string `json:"status"`
	Reason     string `json:"reason,omitempty"`
	ToBank     string `json:"toBank,omitempty"`
	ToName     string `json:"toName,omitempty"`
	Amount     string `json:"amount"`
}

func (s *Server) createTransfer(w http.ResponseWriter, r *http.Request) {
	var req transferRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		problem(w, http.StatusBadRequest, "Invalid JSON")
		return
	}
	amount, err := iso.ParseAmount(req.Amount)
	if err != nil || amount <= 0 {
		problem(w, http.StatusBadRequest, "Amount must be a positive ringgit value like 150.00")
		return
	}
	from, err := s.Ledger.Get(req.FromAccount)
	if err != nil {
		problem(w, http.StatusNotFound, "Source account not found")
		return
	}

	// Resolve the recipient
	toBIC, toAccount, toName, shownName, bankName := strings.ToUpper(req.ToBIC), req.ToAccount, req.ToName, req.ToName, ""
	if req.ProxyType != "" {
		res, err := s.Switch.Lookup(r.Context(), req.ProxyType, req.ProxyValue)
		if errors.Is(err, switchclient.ErrNotFound) {
			problem(w, http.StatusNotFound, "No account is registered to that ID")
			return
		}
		if err != nil {
			problem(w, http.StatusBadGateway, err.Error())
			return
		}
		toBIC, toAccount, toName, shownName, bankName = res.BIC, res.AccountNumber, res.AccountName, res.MaskedName, res.BankName
	}
	if toBIC == "" || toAccount == "" {
		problem(w, http.StatusBadRequest, "Give either proxyType + proxyValue, or toBic + toAccount")
		return
	}

	// Same bank: no switch involved
	if toBIC == s.BIC {
		e2e := iso.NewID("ONUS")
		err := s.Ledger.TransferOnUs(from.Number, toAccount, amount, e2e)
		if err != nil {
			problem(w, ledgerStatus(err), err.Error())
			return
		}
		s.record(&Transfer{EndToEndID: e2e, Direction: "ON_US", Account: from.Number, Counterparty: toAccount, CounterBIC: s.BIC,
			Amount: amount, Reference: req.Reference, Status: "COMPLETED", At: time.Now()})
		writeJSON(w, http.StatusOK, transferResponse{EndToEndID: e2e, Status: "COMPLETED", ToBank: s.Name, ToName: shownName, Amount: iso.FormatAmount(amount)})
		return
	}

	msg := iso.NewPacs008(iso.TransferParams{
		DebtorBIC: s.BIC, DebtorAccount: from.Number, DebtorName: from.Name,
		CreditorBIC: toBIC, CreditorAccount: toAccount, CreditorName: toName,
		AmountSen: amount, Reference: req.Reference,
	})
	e2e := msg.Tx.EndToEndID

	// Reserve the money first, so a customer can't spend it twice while the switch is working
	if err := s.Ledger.Hold(e2e, from.Number, amount); err != nil {
		problem(w, ledgerStatus(err), err.Error())
		return
	}
	s.record(&Transfer{EndToEndID: e2e, Direction: "OUT", Account: from.Number, Counterparty: toAccount, CounterBIC: toBIC,
		Amount: amount, Reference: req.Reference, Status: "PENDING", At: time.Now()})

	st, err := s.Switch.Send(r.Context(), msg)
	if err != nil {
		// The answer was lost; ask the switch what happened before deciding anything
		s.Log.Warn("send failed, asking switch for status", "e2e", e2e, "err", err)
		st, err = s.Switch.Status(context.WithoutCancel(r.Context()), e2e)
		if errors.Is(err, switchclient.ErrNotFound) {
			s.finish(e2e, iso.StatusFor(toBIC, &msg, iso.ReasonTimeoutCreditor, "switch did not receive the transfer"))
		} else if err != nil {
			go s.resolveLater(e2e)
			writeJSON(w, http.StatusAccepted, transferResponse{EndToEndID: e2e, Status: "PENDING", ToBank: bankName, ToName: shownName, Amount: iso.FormatAmount(amount)})
			return
		} else {
			s.finish(e2e, st)
		}
	} else {
		s.finish(e2e, st)
	}

	t, _ := s.transfer(e2e)
	code := http.StatusOK
	if t.Status == "REJECTED" {
		code = http.StatusUnprocessableEntity
	}
	writeJSON(w, code, transferResponse{EndToEndID: e2e, Status: t.Status, Reason: t.Reason, ToBank: bankName, ToName: shownName, Amount: iso.FormatAmount(amount)})
}

// finish applies the switch's verdict to the hold.
func (s *Server) finish(e2e string, st iso.Pacs002) {
	if st.Accepted() {
		if err := s.Ledger.Commit(e2e, e2e); err == nil {
			s.update(e2e, func(t *Transfer) { t.Status = "COMPLETED" })
		}
		return
	}
	if err := s.Ledger.Release(e2e); err == nil {
		s.update(e2e, func(t *Transfer) { t.Status = "REJECTED"; t.Reason = st.ReasonCode() })
	}
}

// resolveLater keeps asking the switch about a transfer whose outcome is unknown.
func (s *Server) resolveLater(e2e string) {
	for i := 0; i < 12; i++ {
		time.Sleep(5 * time.Second)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		st, err := s.Switch.Status(ctx, e2e)
		cancel()
		if err == nil {
			s.finish(e2e, st)
			return
		}
		if errors.Is(err, switchclient.ErrNotFound) {
			_ = s.Ledger.Release(e2e)
			s.update(e2e, func(t *Transfer) { t.Status = "REJECTED"; t.Reason = iso.ReasonTimeoutCreditor })
			return
		}
	}
	s.Log.Error("transfer still unresolved, money stays on hold for manual review", "e2e", e2e)
}

func ledgerStatus(err error) int {
	switch {
	case errors.Is(err, ledger.ErrNoAccount):
		return http.StatusNotFound
	case errors.Is(err, ledger.ErrInsufficientFunds), errors.Is(err, ledger.ErrClosedAccount):
		return http.StatusUnprocessableEntity
	default:
		return http.StatusInternalServerError
	}
}
