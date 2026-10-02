// Package ledger is the bank's core banking system, kept in memory: customer accounts, holds for outgoing
// transfers, and an idempotent journal of incoming credits from the switch.
package ledger

import (
	"errors"
	"sort"
	"sync"
	"time"
)

var (
	ErrNoAccount         = errors.New("account not found")
	ErrClosedAccount     = errors.New("account closed")
	ErrInsufficientFunds = errors.New("insufficient funds")
	ErrNoHold            = errors.New("hold not found")
)

type Account struct {
	Number  string `json:"number"`
	Name    string `json:"name"`
	Balance int64  `json:"balance"` // sen, after holds
	Held    int64  `json:"held"`    // sen reserved for transfers waiting on the switch
	Closed  bool   `json:"closed"`
}

// Entry is one line in the journal.
type Entry struct {
	At        time.Time `json:"at"`
	Account   string    `json:"account"`
	Amount    int64     `json:"amount"` // positive = credit, negative = debit
	Kind      string    `json:"kind"`   // transfer-out, transfer-in, reversal, refund, on-us
	Reference string    `json:"reference"`
}

type hold struct {
	account string
	amount  int64
}

type Ledger struct {
	mu       sync.Mutex
	accounts map[string]*Account
	holds    map[string]hold  // by end-to-end id
	credits  map[string]Entry // incoming credits by end-to-end id (idempotency)
	reversed map[string]bool  // credits already reversed
	journal  []Entry
	now      func() time.Time
}

func New() *Ledger {
	return &Ledger{
		accounts: map[string]*Account{},
		holds:    map[string]hold{},
		credits:  map[string]Entry{},
		reversed: map[string]bool{},
		now:      time.Now,
	}
}

func (l *Ledger) Open(number, name string, balance int64) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.accounts[number] = &Account{Number: number, Name: name, Balance: balance}
}

func (l *Ledger) Close(number string) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	a, ok := l.accounts[number]
	if !ok {
		return ErrNoAccount
	}
	a.Closed = true
	return nil
}

func (l *Ledger) Get(number string) (Account, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	a, ok := l.accounts[number]
	if !ok {
		return Account{}, ErrNoAccount
	}
	return *a, nil
}

func (l *Ledger) Accounts() []Account {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]Account, 0, len(l.accounts))
	for _, a := range l.accounts {
		out = append(out, *a)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Number < out[j].Number })
	return out
}

// Hold reserves money for an outgoing transfer until the switch answers.
func (l *Ledger) Hold(key, number string, amount int64) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	a, ok := l.accounts[number]
	if !ok {
		return ErrNoAccount
	}
	if a.Closed {
		return ErrClosedAccount
	}
	if a.Balance < amount {
		return ErrInsufficientFunds
	}
	a.Balance -= amount
	a.Held += amount
	l.holds[key] = hold{account: number, amount: amount}
	return nil
}

// Commit turns a hold into a debit (the switch accepted the transfer).
func (l *Ledger) Commit(key, reference string) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	h, ok := l.holds[key]
	if !ok {
		return ErrNoHold
	}
	delete(l.holds, key)
	a := l.accounts[h.account]
	a.Held -= h.amount
	l.journal = append(l.journal, Entry{At: l.now(), Account: h.account, Amount: -h.amount, Kind: "transfer-out", Reference: reference})
	return nil
}

// Release gives held money back (the switch rejected the transfer).
func (l *Ledger) Release(key string) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	h, ok := l.holds[key]
	if !ok {
		return ErrNoHold
	}
	delete(l.holds, key)
	a := l.accounts[h.account]
	a.Held -= h.amount
	a.Balance += h.amount
	return nil
}

// Credit applies an incoming transfer once; replaying the same key returns the first result.
// It returns true when the credit was applied by this call.
func (l *Ledger) Credit(key, number string, amount int64, reference string) (bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if _, done := l.credits[key]; done {
		return false, nil
	}
	a, ok := l.accounts[number]
	if !ok {
		return false, ErrNoAccount
	}
	if a.Closed {
		return false, ErrClosedAccount
	}
	a.Balance += amount
	e := Entry{At: l.now(), Account: number, Amount: amount, Kind: "transfer-in", Reference: reference}
	l.credits[key] = e
	l.journal = append(l.journal, e)
	return true, nil
}

// ReverseCredit undoes an incoming credit after the switch cancelled it (camt.056).
// It reports false when there was nothing to reverse (never received, or already reversed).
func (l *Ledger) ReverseCredit(key, reference string) (bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	e, ok := l.credits[key]
	if !ok || l.reversed[key] {
		return false, nil
	}
	a := l.accounts[e.Account]
	a.Balance -= e.Amount
	l.reversed[key] = true
	l.journal = append(l.journal, Entry{At: l.now(), Account: e.Account, Amount: -e.Amount, Kind: "reversal", Reference: reference})
	return true, nil
}

// TransferOnUs moves money between two accounts of this bank without the switch.
func (l *Ledger) TransferOnUs(from, to string, amount int64, reference string) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	src, ok1 := l.accounts[from]
	dst, ok2 := l.accounts[to]
	if !ok1 || !ok2 {
		return ErrNoAccount
	}
	if src.Closed || dst.Closed {
		return ErrClosedAccount
	}
	if src.Balance < amount {
		return ErrInsufficientFunds
	}
	src.Balance -= amount
	dst.Balance += amount
	now := l.now()
	l.journal = append(l.journal,
		Entry{At: now, Account: from, Amount: -amount, Kind: "on-us", Reference: reference},
		Entry{At: now, Account: to, Amount: amount, Kind: "on-us", Reference: reference})
	return nil
}

// Journal returns the latest entries first.
func (l *Ledger) Journal(limit int) []Entry {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]Entry, 0, limit)
	for i := len(l.journal) - 1; i >= 0 && len(out) < limit; i-- {
		out = append(out, l.journal[i])
	}
	return out
}

// NetByReference sums the journal entries for one transfer (by end-to-end id) per account: what the
// bank actually moved for it. Used to reconcile against the switch's settlement report.
func (l *Ledger) NetByReference(reference string) (amount int64, found bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, e := range l.journal {
		if e.Reference == reference && e.Kind != "on-us" {
			amount += e.Amount
			found = true
		}
	}
	return
}
