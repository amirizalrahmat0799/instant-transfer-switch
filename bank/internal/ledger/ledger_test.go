package ledger

import (
	"errors"
	"sync"
	"testing"
)

func TestHoldCommitRelease(t *testing.T) {
	l := New()
	l.Open("A", "Alice", 10_000)

	if err := l.Hold("t1", "A", 4_000); err != nil {
		t.Fatal(err)
	}
	if err := l.Hold("t2", "A", 7_000); !errors.Is(err, ErrInsufficientFunds) {
		t.Fatalf("held money must not be spendable twice, got %v", err)
	}
	a, _ := l.Get("A")
	if a.Balance != 6_000 || a.Held != 4_000 {
		t.Fatalf("after hold: %+v", a)
	}

	_ = l.Commit("t1", "t1")
	a, _ = l.Get("A")
	if a.Balance != 6_000 || a.Held != 0 {
		t.Fatalf("after commit: %+v", a)
	}

	_ = l.Hold("t3", "A", 1_000)
	_ = l.Release("t3")
	a, _ = l.Get("A")
	if a.Balance != 6_000 || a.Held != 0 {
		t.Fatalf("after release: %+v", a)
	}
	if err := l.Release("t3"); !errors.Is(err, ErrNoHold) {
		t.Fatal("a hold can only be released once")
	}
}

func TestCreditIsIdempotentAndReversible(t *testing.T) {
	l := New()
	l.Open("B", "Bob", 0)

	applied, err := l.Credit("e2e-1", "B", 2_500, "e2e-1")
	if err != nil || !applied {
		t.Fatal("first credit should apply")
	}
	applied, _ = l.Credit("e2e-1", "B", 2_500, "e2e-1")
	if applied {
		t.Fatal("a replayed credit must not apply twice")
	}
	if b, _ := l.Get("B"); b.Balance != 2_500 {
		t.Fatalf("balance %d", b.Balance)
	}

	if ok, _ := l.ReverseCredit("e2e-1", "e2e-1"); !ok {
		t.Fatal("reversal should apply")
	}
	if ok, _ := l.ReverseCredit("e2e-1", "e2e-1"); ok {
		t.Fatal("a credit can only be reversed once")
	}
	if ok, _ := l.ReverseCredit("never-received", "x"); ok {
		t.Fatal("nothing to reverse for an unknown transfer")
	}
	if b, _ := l.Get("B"); b.Balance != 0 {
		t.Fatalf("balance after reversal %d", b.Balance)
	}
	if net, found := l.NetByReference("e2e-1"); !found || net != 0 {
		t.Fatalf("net by reference %d %v", net, found)
	}
}

func TestCreditErrors(t *testing.T) {
	l := New()
	l.Open("C", "Carol", 0)
	_ = l.Close("C")
	if _, err := l.Credit("k", "missing", 1, "k"); !errors.Is(err, ErrNoAccount) {
		t.Error("unknown account")
	}
	if _, err := l.Credit("k", "C", 1, "k"); !errors.Is(err, ErrClosedAccount) {
		t.Error("closed account")
	}
}

func TestConcurrentHoldsNeverOverdraw(t *testing.T) {
	l := New()
	l.Open("A", "Alice", 100_00)
	var wg sync.WaitGroup
	var mu sync.Mutex
	ok := 0
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if l.Hold(string(rune('a'+i)), "A", 10_00) == nil {
				mu.Lock()
				ok++
				mu.Unlock()
			}
		}(i)
	}
	wg.Wait()
	a, _ := l.Get("A")
	if ok != 10 || a.Balance != 0 {
		t.Fatalf("%d holds succeeded, balance %d", ok, a.Balance)
	}
}
