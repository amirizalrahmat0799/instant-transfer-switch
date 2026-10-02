package server

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/switchclient"
)

type Break struct {
	EndToEndID  string `json:"endToEndId"`
	Direction   string `json:"direction"`
	SwitchSays  int64  `json:"switchAmount"`
	BankBooked  int64  `json:"bankAmount"`
	Explanation string `json:"explanation"`
}

type Reconciliation struct {
	Cycle      int64   `json:"cycle"`
	Checked    int     `json:"checked"`
	Matched    int     `json:"matched"`
	Breaks     []Break `json:"breaks"`
	Reconciled bool    `json:"reconciled"`
}

// reconcile checks every transaction the switch settled for this bank in a cycle against what this bank
// actually booked. Any difference is a "break" that operations would investigate.
func (s *Server) reconcile(w http.ResponseWriter, r *http.Request) {
	cycle, err := strconv.ParseInt(r.PathValue("cycle"), 10, 64)
	if err != nil || cycle <= 0 {
		problem(w, http.StatusBadRequest, "Cycle must be a positive number")
		return
	}
	txs, err := s.Switch.CycleTransactions(r.Context(), cycle)
	if errors.Is(err, switchclient.ErrNotFound) {
		problem(w, http.StatusNotFound, "Unknown cycle")
		return
	}
	if err != nil {
		problem(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, s.compare(cycle, txs))
}

func (s *Server) compare(cycle int64, txs []switchclient.CycleTransaction) Reconciliation {
	rec := Reconciliation{Cycle: cycle, Breaks: []Break{}}
	for _, tx := range txs {
		rec.Checked++
		expected := tx.Amount
		if tx.Direction == "SENT" {
			expected = -tx.Amount
		}
		booked, found := s.Ledger.NetByReference(tx.EndToEndID)
		switch {
		case !found:
			rec.Breaks = append(rec.Breaks, Break{tx.EndToEndID, tx.Direction, expected, 0, "settled by the switch but not booked by the bank"})
		case booked != expected:
			rec.Breaks = append(rec.Breaks, Break{tx.EndToEndID, tx.Direction, expected, booked, "amounts differ"})
		default:
			rec.Matched++
		}
	}
	rec.Reconciled = len(rec.Breaks) == 0
	return rec
}
