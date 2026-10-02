// Package server is the bank's HTTP API: a small customer API (accounts, pay by phone number or account,
// history), the ISO 20022 endpoints the switch calls, and admin tools for demos (chaos and reconciliation).
package server

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"sort"
	"sync"
	"time"

	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/iso"
	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/ledger"
	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/switchclient"
)

// Switch is what the bank needs from the central switch (an interface so tests can fake it).
type Switch interface {
	RegisterProxy(ctx context.Context, p switchclient.ProxyRegistration) error
	Lookup(ctx context.Context, proxyType, value string) (switchclient.Resolution, error)
	Send(ctx context.Context, msg iso.Pacs008) (iso.Pacs002, error)
	Status(ctx context.Context, endToEndID string) (iso.Pacs002, error)
	CycleTransactions(ctx context.Context, cycle int64) ([]switchclient.CycleTransaction, error)
}

// Transfer is the bank's own record of an interbank transfer, in either direction.
type Transfer struct {
	EndToEndID   string    `json:"endToEndId"`
	Direction    string    `json:"direction"` // OUT, IN or ON_US
	Account      string    `json:"account"`
	Counterparty string    `json:"counterparty"`
	CounterBIC   string    `json:"counterpartyBic"`
	Amount       int64     `json:"amount"` // sen
	Reference    string    `json:"reference,omitempty"`
	Status       string    `json:"status"` // COMPLETED, REJECTED, PENDING, REVERSED
	Reason       string    `json:"reason,omitempty"`
	At           time.Time `json:"at"`
}

// Chaos lets a demo make this bank slow, rejecting or offline, to show how the switch copes.
type Chaos struct {
	DelayMs   int  `json:"delayMs"`
	RejectAll bool `json:"rejectAll"`
	Offline   bool `json:"offline"`
}

type Server struct {
	BIC    string
	Name   string
	Ledger *ledger.Ledger
	Switch Switch
	Log    *slog.Logger

	mu        sync.Mutex
	transfers map[string]*Transfer
	chaos     Chaos
}

func New(bic, name string, l *ledger.Ledger, sw Switch, log *slog.Logger) *Server {
	return &Server{BIC: bic, Name: name, Ledger: l, Switch: sw, Log: log, transfers: map[string]*Transfer{}}
}

func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.health)

	// Customer API
	mux.HandleFunc("GET /accounts", s.listAccounts)
	mux.HandleFunc("GET /accounts/{number}", s.getAccount)
	mux.HandleFunc("POST /proxies", s.registerProxy)
	mux.HandleFunc("GET /lookup/{type}/{value}", s.lookup)
	mux.HandleFunc("POST /transfers", s.createTransfer)
	mux.HandleFunc("GET /transfers", s.listTransfers)

	// Called by the switch
	mux.HandleFunc("POST /iso/pacs.008", s.inboundCredit)
	mux.HandleFunc("POST /iso/camt.056", s.cancellation)

	// Demo tools
	mux.HandleFunc("GET /admin/chaos", s.getChaos)
	mux.HandleFunc("POST /admin/chaos", s.setChaos)
	mux.HandleFunc("GET /admin/reconcile/{cycle}", s.reconcile)
	return logRequests(s.Log, mux)
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	if s.currentChaos().Offline {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "DOWN", "bic": s.BIC})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP", "bic": s.BIC, "name": s.Name})
}

func (s *Server) currentChaos() Chaos {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.chaos
}

func (s *Server) getChaos(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, s.currentChaos())
}

func (s *Server) setChaos(w http.ResponseWriter, r *http.Request) {
	var c Chaos
	if err := json.NewDecoder(r.Body).Decode(&c); err != nil || c.DelayMs < 0 || c.DelayMs > 60_000 {
		problem(w, http.StatusBadRequest, "Body must be {delayMs (0-60000), rejectAll, offline}")
		return
	}
	s.mu.Lock()
	s.chaos = c
	s.mu.Unlock()
	s.Log.Info("chaos updated", "delayMs", c.DelayMs, "rejectAll", c.RejectAll, "offline", c.Offline)
	writeJSON(w, http.StatusOK, c)
}

func (s *Server) record(t *Transfer) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cp := *t
	s.transfers[t.EndToEndID] = &cp
}

func (s *Server) update(e2e string, fn func(t *Transfer)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if t, ok := s.transfers[e2e]; ok {
		fn(t)
	}
}

func (s *Server) transfer(e2e string) (Transfer, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.transfers[e2e]
	if !ok {
		return Transfer{}, false
	}
	return *t, true
}

func (s *Server) listTransfers(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	out := make([]Transfer, 0, len(s.transfers))
	for _, t := range s.transfers {
		out = append(out, *t)
	}
	s.mu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i].At.After(out[j].At) })
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) listAccounts(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, s.Ledger.Accounts())
}

func (s *Server) getAccount(w http.ResponseWriter, r *http.Request) {
	a, err := s.Ledger.Get(r.PathValue("number"))
	if err != nil {
		problem(w, http.StatusNotFound, "Account not found")
		return
	}
	writeJSON(w, http.StatusOK, a)
}

// ---------------------------------------------------------------------------------------------

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// problem writes an RFC 9457 problem details body, like the switch does.
func problem(w http.ResponseWriter, status int, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"status": status, "title": http.StatusText(status), "detail": detail})
}

func writeXML(w http.ResponseWriter, status int, body []byte) {
	w.Header().Set("Content-Type", "application/xml")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func logRequests(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		if r.URL.Path != "/health" {
			log.Info("http", "method", r.Method, "path", r.URL.Path, "status", rec.status, "ms", time.Since(start).Milliseconds())
		}
	})
}
