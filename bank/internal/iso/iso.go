// Package iso holds the subset of ISO 20022 messages the switch and the banks exchange:
//
//	pacs.008  FI to FI customer credit transfer   (bank -> switch -> bank)
//	pacs.002  payment status report                (bank -> switch -> bank)
//	camt.056  payment cancellation request         (switch -> bank, after a timeout)
//	camt.029  resolution of investigation          (bank -> switch, answer to camt.056)
package iso

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"strings"
	"time"
)

const (
	NsPacs008 = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"
	NsPacs002 = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"
	NsCamt056 = "urn:iso:std:iso:20022:tech:xsd:camt.056.001.08"
	NsCamt029 = "urn:iso:std:iso:20022:tech:xsd:camt.029.001.09"

	StatusAccepted = "ACSC" // accepted, settlement completed
	StatusRejected = "RJCT"

	CancelDone     = "CNCL" // the credit was reversed
	CancelRejected = "RJCR" // nothing to cancel (e.g. never received)
)

// Reason codes used in this project (ISO 20022 external status reason codes).
const (
	ReasonIncorrectAccount = "AC01" // creditor account number invalid or missing
	ReasonClosedAccount    = "AC04"
	ReasonInsufficientFund = "AM04"
	ReasonTimeoutCreditor  = "AB05"
	ReasonOfflineCreditor  = "AB08"
	ReasonInvalidFormat    = "FF01"
	ReasonUnknownBank      = "RC01"
	ReasonNoOriginal       = "NOOR" // camt.029: original transaction not received
)

// ---------------------------------------------------------------------------------------------
// pacs.008
// ---------------------------------------------------------------------------------------------

type Amount struct {
	Currency string `xml:"Ccy,attr"`
	Value    string `xml:",chardata"`
}

type Agent struct {
	BIC string `xml:"FinInstnId>BICFI"`
}

type Party struct {
	Name string `xml:"Nm"`
}

type Account struct {
	ID string `xml:"Id>Othr>Id"`
}

type CreditTransferTx struct {
	EndToEndID     string  `xml:"PmtId>EndToEndId"`
	TxID           string  `xml:"PmtId>TxId"`
	Amount         Amount  `xml:"IntrBkSttlmAmt"`
	ChargeBearer   string  `xml:"ChrgBr"`
	Debtor         Party   `xml:"Dbtr"`
	DebtorAccount  Account `xml:"DbtrAcct"`
	DebtorAgent    Agent   `xml:"DbtrAgt"`
	CreditorAgent  Agent   `xml:"CdtrAgt"`
	Creditor       Party   `xml:"Cdtr"`
	CreditorAcct   Account `xml:"CdtrAcct"`
	RemittanceInfo string  `xml:"RmtInf>Ustrd,omitempty"`
}

type GroupHeader struct {
	MsgID            string `xml:"MsgId"`
	CreatedAt        string `xml:"CreDtTm"`
	NumberOfTxs      string `xml:"NbOfTxs,omitempty"`
	SettlementMethod string `xml:"SttlmInf>SttlmMtd,omitempty"`
}

type Pacs008 struct {
	XMLName xml.Name         `xml:"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08 Document"`
	Header  GroupHeader      `xml:"FIToFICstmrCdtTrf>GrpHdr"`
	Tx      CreditTransferTx `xml:"FIToFICstmrCdtTrf>CdtTrfTxInf"`
}

// Sen returns the settlement amount in sen.
func (p *Pacs008) Sen() (int64, error) { return ParseAmount(p.Tx.Amount.Value) }

// Validate checks the fields the switch and banks rely on.
func (p *Pacs008) Validate() error {
	t := p.Tx
	missing := []string{}
	for name, v := range map[string]string{
		"MsgId": p.Header.MsgID, "EndToEndId": t.EndToEndID, "TxId": t.TxID,
		"DbtrAgt": t.DebtorAgent.BIC, "CdtrAgt": t.CreditorAgent.BIC, "CdtrAcct": t.CreditorAcct.ID, "DbtrAcct": t.DebtorAccount.ID,
	} {
		if strings.TrimSpace(v) == "" {
			missing = append(missing, name)
		}
	}
	if len(missing) > 0 {
		return fmt.Errorf("missing %s", strings.Join(missing, ", "))
	}
	if t.Amount.Currency != "MYR" {
		return fmt.Errorf("unsupported currency %q", t.Amount.Currency)
	}
	sen, err := p.Sen()
	if err != nil {
		return err
	}
	if sen <= 0 {
		return fmt.Errorf("amount must be positive")
	}
	return nil
}

// NewPacs008 builds a single-transaction credit transfer.
type TransferParams struct {
	DebtorBIC, DebtorAccount, DebtorName       string
	CreditorBIC, CreditorAccount, CreditorName string
	AmountSen                                  int64
	Reference                                  string
}

func NewPacs008(p TransferParams) Pacs008 {
	now := Now()
	return Pacs008{
		Header: GroupHeader{MsgID: NewID(p.DebtorBIC), CreatedAt: now, NumberOfTxs: "1", SettlementMethod: "CLRG"},
		Tx: CreditTransferTx{
			EndToEndID:     NewID("E2E"),
			TxID:           NewID(p.DebtorBIC[:4]),
			Amount:         Amount{Currency: "MYR", Value: FormatAmount(p.AmountSen)},
			ChargeBearer:   "SLEV",
			Debtor:         Party{Name: p.DebtorName},
			DebtorAccount:  Account{ID: p.DebtorAccount},
			DebtorAgent:    Agent{BIC: p.DebtorBIC},
			CreditorAgent:  Agent{BIC: p.CreditorBIC},
			Creditor:       Party{Name: p.CreditorName},
			CreditorAcct:   Account{ID: p.CreditorAccount},
			RemittanceInfo: p.Reference,
		},
	}
}

// ---------------------------------------------------------------------------------------------
// pacs.002
// ---------------------------------------------------------------------------------------------

type StatusReason struct {
	Code       string `xml:"Rsn>Cd"`
	Additional string `xml:"AddtlInf,omitempty"`
}

type TxStatus struct {
	OriginalEndToEndID string        `xml:"OrgnlEndToEndId"`
	OriginalTxID       string        `xml:"OrgnlTxId"`
	Status             string        `xml:"TxSts"`
	Reason             *StatusReason `xml:"StsRsnInf,omitempty"`
}

type Pacs002 struct {
	XMLName       xml.Name    `xml:"urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10 Document"`
	Header        GroupHeader `xml:"FIToFIPmtStsRpt>GrpHdr"`
	OriginalMsgID string      `xml:"FIToFIPmtStsRpt>OrgnlGrpInfAndSts>OrgnlMsgId"`
	OriginalMsgNm string      `xml:"FIToFIPmtStsRpt>OrgnlGrpInfAndSts>OrgnlMsgNmId"`
	Tx            TxStatus    `xml:"FIToFIPmtStsRpt>TxInfAndSts"`
}

func (p *Pacs002) Accepted() bool { return p.Tx.Status == StatusAccepted }

func (p *Pacs002) ReasonCode() string {
	if p.Tx.Reason == nil {
		return ""
	}
	return p.Tx.Reason.Code
}

// StatusFor answers a pacs.008. Pass an empty reason for an acceptance.
func StatusFor(senderBIC string, req *Pacs008, reason, info string) Pacs002 {
	st := Pacs002{
		Header:        GroupHeader{MsgID: NewID(senderBIC), CreatedAt: Now()},
		OriginalMsgID: req.Header.MsgID,
		OriginalMsgNm: "pacs.008.001.08",
		Tx:            TxStatus{OriginalEndToEndID: req.Tx.EndToEndID, OriginalTxID: req.Tx.TxID, Status: StatusAccepted},
	}
	if reason != "" {
		st.Tx.Status = StatusRejected
		st.Tx.Reason = &StatusReason{Code: reason, Additional: info}
	}
	return st
}

// ---------------------------------------------------------------------------------------------
// camt.056 / camt.029
// ---------------------------------------------------------------------------------------------

type CancellationTx struct {
	OriginalEndToEndID string `xml:"OrgnlEndToEndId"`
	OriginalTxID       string `xml:"OrgnlTxId"`
	OriginalAmount     Amount `xml:"OrgnlIntrBkSttlmAmt"`
	ReasonCode         string `xml:"CxlRsnInf>Rsn>Cd"`
}

type Camt056 struct {
	XMLName      xml.Name       `xml:"urn:iso:std:iso:20022:tech:xsd:camt.056.001.08 Document"`
	AssignmentID string         `xml:"FIToFIPmtCxlReq>Assgnmt>Id"`
	CreatedAt    string         `xml:"FIToFIPmtCxlReq>Assgnmt>CreDtTm"`
	Tx           CancellationTx `xml:"FIToFIPmtCxlReq>Undrlyg>TxInf"`
}

type Camt029 struct {
	XMLName            xml.Name `xml:"urn:iso:std:iso:20022:tech:xsd:camt.029.001.09 Document"`
	AssignmentID       string   `xml:"RsltnOfInvstgtn>Assgnmt>Id"`
	CreatedAt          string   `xml:"RsltnOfInvstgtn>Assgnmt>CreDtTm"`
	Confirmation       string   `xml:"RsltnOfInvstgtn>Sts>Conf"`
	OriginalEndToEndID string   `xml:"RsltnOfInvstgtn>CxlDtls>TxInfAndSts>OrgnlEndToEndId"`
	CancellationStatus string   `xml:"RsltnOfInvstgtn>CxlDtls>TxInfAndSts>TxCxlSts"`
	ReasonCode         string   `xml:"RsltnOfInvstgtn>CxlDtls>TxInfAndSts>CxlStsRsnInf>Rsn>Cd,omitempty"`
}

func ResolutionFor(bic string, req *Camt056, status, reason string) Camt029 {
	return Camt029{
		AssignmentID:       NewID(bic),
		CreatedAt:          Now(),
		Confirmation:       status,
		OriginalEndToEndID: req.Tx.OriginalEndToEndID,
		CancellationStatus: status,
		ReasonCode:         reason,
	}
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

// Marshal renders a message with an XML declaration.
func Marshal(v any) ([]byte, error) {
	body, err := xml.MarshalIndent(v, "", "  ")
	if err != nil {
		return nil, err
	}
	return append([]byte(xml.Header), body...), nil
}

// Unmarshal parses a message and rejects documents with the wrong root namespace.
func Unmarshal(data []byte, v any) error {
	return xml.Unmarshal(data, v)
}

var myt = time.FixedZone("MYT", 8*3600)

// Now is an ISO 8601 timestamp in Malaysia time.
func Now() string { return time.Now().In(myt).Format("2006-01-02T15:04:05.000-07:00") }

// NewID returns a 32-character identifier: a prefix, the date and random hex (ISO ids allow up to 35 characters).
func NewID(prefix string) string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	id := prefix + time.Now().In(myt).Format("20060102") + strings.ToUpper(hex.EncodeToString(b))
	if len(id) > 35 {
		id = id[:35]
	}
	return id
}
