package iso

import (
	"strings"
	"testing"
)

func TestAmounts(t *testing.T) {
	for in, want := range map[string]int64{"150": 15000, "150.5": 15050, "150.50": 15050, "0.01": 1, "19.99": 1999} {
		got, err := ParseAmount(in)
		if err != nil || got != want {
			t.Errorf("ParseAmount(%q) = %d, %v; want %d", in, got, err, want)
		}
	}
	for _, bad := range []string{"", "1.234", "-5", "1e3", "12.", ".5", "abc"} {
		if _, err := ParseAmount(bad); err == nil {
			t.Errorf("ParseAmount(%q) should fail", bad)
		}
	}
	if FormatAmount(15050) != "150.50" || FormatAmount(5) != "0.05" {
		t.Error("FormatAmount")
	}
}

func TestPacs008RoundTrip(t *testing.T) {
	msg := NewPacs008(TransferParams{
		DebtorBIC: "ALFAMYKL", DebtorAccount: "1100000001", DebtorName: "Aisyah Rahman",
		CreditorBIC: "BRVOMYKL", CreditorAccount: "2200000001", CreditorName: "Hafiz Ismail",
		AmountSen: 12550, Reference: "Lunch <& dinner>",
	})
	out, err := Marshal(msg)
	if err != nil {
		t.Fatal(err)
	}
	xml := string(out)
	for _, want := range []string{
		`<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">`,
		`<IntrBkSttlmAmt Ccy="MYR">125.50</IntrBkSttlmAmt>`,
		`<DbtrAgt>`, `<BICFI>BRVOMYKL</BICFI>`, `<SttlmMtd>CLRG</SttlmMtd>`, `Lunch &lt;&amp; dinner&gt;`,
	} {
		if !strings.Contains(xml, want) {
			t.Errorf("missing %s in\n%s", want, xml)
		}
	}

	var back Pacs008
	if err := Unmarshal(out, &back); err != nil {
		t.Fatal(err)
	}
	if err := back.Validate(); err != nil {
		t.Fatal(err)
	}
	if back.Tx != msg.Tx {
		t.Errorf("round trip changed the transaction:\n%+v\n%+v", back.Tx, msg.Tx)
	}
}

func TestRejectsWrongDocument(t *testing.T) {
	var p Pacs008
	err := Unmarshal([]byte(`<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"></Document>`), &p)
	if err == nil {
		t.Fatal("a pacs.002 must not parse as a pacs.008")
	}
}

func TestValidate(t *testing.T) {
	msg := NewPacs008(TransferParams{DebtorBIC: "ALFAMYKL", DebtorAccount: "1", CreditorBIC: "BRVOMYKL", CreditorAccount: "2", AmountSen: 100})
	msg.Tx.Amount.Currency = "USD"
	if err := msg.Validate(); err == nil || !strings.Contains(err.Error(), "currency") {
		t.Errorf("expected a currency error, got %v", err)
	}
	msg.Tx.Amount = Amount{Currency: "MYR", Value: "0.00"}
	if err := msg.Validate(); err == nil {
		t.Error("zero amount must be rejected")
	}
	msg.Tx.CreditorAcct.ID = ""
	if err := msg.Validate(); err == nil || !strings.Contains(err.Error(), "CdtrAcct") {
		t.Errorf("expected missing CdtrAcct, got %v", err)
	}
}

func TestStatusReport(t *testing.T) {
	req := NewPacs008(TransferParams{DebtorBIC: "ALFAMYKL", DebtorAccount: "1", CreditorBIC: "BRVOMYKL", CreditorAccount: "2", AmountSen: 100})
	ok := StatusFor("BRVOMYKL", &req, "", "")
	if !ok.Accepted() || ok.ReasonCode() != "" || ok.Tx.OriginalEndToEndID != req.Tx.EndToEndID {
		t.Errorf("acceptance: %+v", ok)
	}
	rj := StatusFor("BRVOMYKL", &req, ReasonIncorrectAccount, "no such account")
	out, _ := Marshal(rj)
	var back Pacs002
	if err := Unmarshal(out, &back); err != nil {
		t.Fatal(err)
	}
	if back.Accepted() || back.ReasonCode() != "AC01" || !strings.Contains(string(out), "<TxSts>RJCT</TxSts>") {
		t.Errorf("rejection: %s", out)
	}
}

func TestIDs(t *testing.T) {
	a, b := NewID("ALFAMYKL"), NewID("ALFAMYKL")
	if a == b || len(a) > 35 || !strings.HasPrefix(a, "ALFAMYKL") {
		t.Errorf("ids: %s %s", a, b)
	}
}
