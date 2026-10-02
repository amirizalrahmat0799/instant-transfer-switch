package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/iso"
	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/ledger"
	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/switchclient"
)

// fakeSwitch answers like the real switch would, with a verdict chosen by the test.
type fakeSwitch struct {
	verdict   string // "" accepts, otherwise a reason code
	sendErr   error
	status    *iso.Pacs002
	statusErr error
	sent      []iso.Pacs008
	cycle     []switchclient.CycleTransaction
}

func (f *fakeSwitch) RegisterProxy(context.Context, switchclient.ProxyRegistration) error { return nil }

func (f *fakeSwitch) Lookup(_ context.Context, typ, value string) (switchclient.Resolution, error) {
	if typ == "MOBILE" && value == "0198765401" {
		return switchclient.Resolution{BIC: "BRVOMYKL", BankName: "Bravo Bank", AccountNumber: "2200000001", AccountName: "Hafiz Ismail", MaskedName: "HAFIZ I****"}, nil
	}
	return switchclient.Resolution{}, switchclient.ErrNotFound
}

func (f *fakeSwitch) Send(_ context.Context, msg iso.Pacs008) (iso.Pacs002, error) {
	f.sent = append(f.sent, msg)
	if f.sendErr != nil {
		return iso.Pacs002{}, f.sendErr
	}
	return iso.StatusFor("SWITCH", &msg, f.verdict, ""), nil
}

func (f *fakeSwitch) Status(context.Context, string) (iso.Pacs002, error) {
	if f.status != nil {
		return *f.status, nil
	}
	return iso.Pacs002{}, f.statusErr
}

func (f *fakeSwitch) CycleTransactions(context.Context, int64) ([]switchclient.CycleTransaction, error) {
	return f.cycle, nil
}

func newBank(t *testing.T, sw *fakeSwitch) (*Server, http.Handler) {
	t.Helper()
	l := ledger.New()
	l.Open("1100000001", "Aisyah Rahman", 100_00)
	l.Open("1100000002", "Daniel Tan", 0)
	s := New("ALFAMYKL", "Alfa Bank", l, sw, slog.New(slog.NewTextHandler(io.Discard, nil)))
	return s, s.Routes()
}

func call(t *testing.T, h http.Handler, method, path, contentType, body string) (*httptest.ResponseRecorder, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var out map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return rec, out
}

func balance(t *testing.T, s *Server, acct string) (int64, int64) {
	a, err := s.Ledger.Get(acct)
	if err != nil {
		t.Fatal(err)
	}
	return a.Balance, a.Held
}

func TestPayByPhoneNumber(t *testing.T) {
	sw := &fakeSwitch{}
	s, h := newBank(t, sw)

	rec, out := call(t, h, "POST", "/transfers", "application/json",
		`{"fromAccount":"1100000001","proxyType":"MOBILE","proxyValue":"0198765401","amount":"25.50","reference":"Lunch"}`)

	if rec.Code != 200 || out["status"] != "COMPLETED" || out["toName"] != "HAFIZ I****" {
		t.Fatalf("%d %v", rec.Code, out)
	}
	if bal, held := balance(t, s, "1100000001"); bal != 100_00-25_50 || held != 0 {
		t.Fatalf("balance %d held %d", bal, held)
	}
	msg := sw.sent[0]
	if msg.Tx.CreditorAgent.BIC != "BRVOMYKL" || msg.Tx.CreditorAcct.ID != "2200000001" || msg.Tx.Amount.Value != "25.50" {
		t.Fatalf("pacs.008 sent: %+v", msg.Tx)
	}
}

func TestRejectedTransferReleasesTheHold(t *testing.T) {
	s, h := newBank(t, &fakeSwitch{verdict: iso.ReasonIncorrectAccount})
	rec, out := call(t, h, "POST", "/transfers", "application/json",
		`{"fromAccount":"1100000001","toBic":"BRVOMYKL","toAccount":"9999","amount":"10.00"}`)
	if rec.Code != 422 || out["status"] != "REJECTED" || out["reason"] != "AC01" {
		t.Fatalf("%d %v", rec.Code, out)
	}
	if bal, held := balance(t, s, "1100000001"); bal != 100_00 || held != 0 {
		t.Fatalf("money must come back: balance %d held %d", bal, held)
	}
}

func TestLostResponseAsksTheSwitch(t *testing.T) {
	accepted := iso.Pacs002{Tx: iso.TxStatus{Status: iso.StatusAccepted}}
	s, h := newBank(t, &fakeSwitch{sendErr: errors.New("connection reset"), status: &accepted})
	_, out := call(t, h, "POST", "/transfers", "application/json",
		`{"fromAccount":"1100000001","toBic":"BRVOMYKL","toAccount":"2200000001","amount":"10.00"}`)
	if out["status"] != "COMPLETED" {
		t.Fatalf("the switch completed it, so the bank must too: %v", out)
	}
	if bal, _ := balance(t, s, "1100000001"); bal != 90_00 {
		t.Fatalf("balance %d", bal)
	}
}

func TestLostRequestIsRefunded(t *testing.T) {
	s, h := newBank(t, &fakeSwitch{sendErr: errors.New("connection refused"), statusErr: switchclient.ErrNotFound})
	_, out := call(t, h, "POST", "/transfers", "application/json",
		`{"fromAccount":"1100000001","toBic":"BRVOMYKL","toAccount":"2200000001","amount":"10.00"}`)
	if out["status"] != "REJECTED" {
		t.Fatalf("%v", out)
	}
	if bal, held := balance(t, s, "1100000001"); bal != 100_00 || held != 0 {
		t.Fatalf("balance %d held %d", bal, held)
	}
}

func TestInsufficientFundsNeverReachesTheSwitch(t *testing.T) {
	sw := &fakeSwitch{}
	_, h := newBank(t, sw)
	rec, _ := call(t, h, "POST", "/transfers", "application/json",
		`{"fromAccount":"1100000001","toBic":"BRVOMYKL","toAccount":"2200000001","amount":"1000.00"}`)
	if rec.Code != 422 || len(sw.sent) != 0 {
		t.Fatalf("%d, sent %d", rec.Code, len(sw.sent))
	}
}

func TestOnUsTransferSkipsTheSwitch(t *testing.T) {
	sw := &fakeSwitch{}
	s, h := newBank(t, sw)
	_, out := call(t, h, "POST", "/transfers", "application/json",
		`{"fromAccount":"1100000001","toBic":"ALFAMYKL","toAccount":"1100000002","amount":"5.00"}`)
	if out["status"] != "COMPLETED" || len(sw.sent) != 0 {
		t.Fatalf("%v sent=%d", out, len(sw.sent))
	}
	if bal, _ := balance(t, s, "1100000002"); bal != 5_00 {
		t.Fatalf("balance %d", bal)
	}
}

func inbound(to, account string, amount int64) (iso.Pacs008, string) {
	msg := iso.NewPacs008(iso.TransferParams{
		DebtorBIC: "BRVOMYKL", DebtorAccount: "2200000001", DebtorName: "Hafiz Ismail",
		CreditorBIC: to, CreditorAccount: account, CreditorName: "Aisyah Rahman", AmountSen: amount, Reference: "Rent share",
	})
	body, _ := iso.Marshal(msg)
	return msg, string(body)
}

func parseStatus(t *testing.T, body *bytes.Buffer) iso.Pacs002 {
	var st iso.Pacs002
	if err := iso.Unmarshal(body.Bytes(), &st); err != nil {
		t.Fatalf("not a pacs.002: %v\n%s", err, body)
	}
	return st
}

func TestInboundCreditIsIdempotent(t *testing.T) {
	s, h := newBank(t, &fakeSwitch{})
	msg, body := inbound("ALFAMYKL", "1100000001", 40_00)

	for i := 0; i < 2; i++ {
		rec, _ := call(t, h, "POST", "/iso/pacs.008", "application/xml", body)
		st := parseStatus(t, rec.Body)
		if !st.Accepted() || st.Tx.OriginalEndToEndID != msg.Tx.EndToEndID {
			t.Fatalf("attempt %d: %+v", i, st.Tx)
		}
	}
	if bal, _ := balance(t, s, "1100000001"); bal != 140_00 {
		t.Fatalf("credited twice? balance %d", bal)
	}
}

func TestInboundRejections(t *testing.T) {
	_, h := newBank(t, &fakeSwitch{})
	cases := map[string][2]string{
		"unknown account": {"ALFAMYKL", "0000"},
		"wrong bank":      {"BRVOMYKL", "1100000001"},
	}
	want := map[string]string{"unknown account": "AC01", "wrong bank": "RC01"}
	for name, c := range cases {
		_, body := inbound(c[0], c[1], 1_00)
		rec, _ := call(t, h, "POST", "/iso/pacs.008", "application/xml", body)
		if st := parseStatus(t, rec.Body); st.Accepted() || st.ReasonCode() != want[name] {
			t.Errorf("%s: %+v", name, st.Tx)
		}
	}
	rec, _ := call(t, h, "POST", "/iso/pacs.008", "application/xml", "<not-iso/>")
	if rec.Code != 400 {
		t.Errorf("garbage should be a 400, got %d", rec.Code)
	}
}

func TestCancellationReversesOnce(t *testing.T) {
	s, h := newBank(t, &fakeSwitch{})
	msg, body := inbound("ALFAMYKL", "1100000001", 30_00)
	call(t, h, "POST", "/iso/pacs.008", "application/xml", body)

	cxl := iso.Camt056{AssignmentID: "X", CreatedAt: iso.Now(), Tx: iso.CancellationTx{
		OriginalEndToEndID: msg.Tx.EndToEndID, OriginalTxID: msg.Tx.TxID,
		OriginalAmount: msg.Tx.Amount, ReasonCode: iso.ReasonTimeoutCreditor,
	}}
	cxlBody, _ := iso.Marshal(cxl)

	statuses := []string{}
	for i := 0; i < 2; i++ {
		rec, _ := call(t, h, "POST", "/iso/camt.056", "application/xml", string(cxlBody))
		var res iso.Camt029
		if err := iso.Unmarshal(rec.Body.Bytes(), &res); err != nil {
			t.Fatal(err)
		}
		statuses = append(statuses, res.CancellationStatus)
	}
	if statuses[0] != "CNCL" || statuses[1] != "RJCR" {
		t.Fatalf("statuses %v", statuses)
	}
	if bal, _ := balance(t, s, "1100000001"); bal != 100_00 {
		t.Fatalf("balance %d", bal)
	}
}

func TestChaosOfflineFailsHealth(t *testing.T) {
	_, h := newBank(t, &fakeSwitch{})
	call(t, h, "POST", "/admin/chaos", "application/json", `{"offline":true}`)
	rec, _ := call(t, h, "GET", "/health", "", "")
	if rec.Code != 503 {
		t.Fatalf("health %d", rec.Code)
	}
	_, body := inbound("ALFAMYKL", "1100000001", 1_00)
	rec, _ = call(t, h, "POST", "/iso/pacs.008", "application/xml", body)
	if rec.Code != 503 {
		t.Fatalf("offline bank must not answer, got %d", rec.Code)
	}
}

func TestReconciliationFindsBreaks(t *testing.T) {
	sw := &fakeSwitch{}
	s, h := newBank(t, sw)
	_, out := call(t, h, "POST", "/transfers", "application/json",
		`{"fromAccount":"1100000001","toBic":"BRVOMYKL","toAccount":"2200000001","amount":"12.00"}`)
	e2e := out["endToEndId"].(string)

	sw.cycle = []switchclient.CycleTransaction{
		{EndToEndID: e2e, Direction: "SENT", Amount: 12_00},
		{EndToEndID: "MISSING", Direction: "RECEIVED", Amount: 5_00},
	}
	_, rec := call(t, h, "GET", "/admin/reconcile/1", "", "")
	if rec["matched"].(float64) != 1 || rec["reconciled"] != false || len(rec["breaks"].([]any)) != 1 {
		t.Fatalf("%v", rec)
	}
	_ = s
}
