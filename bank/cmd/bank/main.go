// Command bank runs a participant bank simulator that connects to the instant transfer switch.
//
// Configuration (environment):
//
//	BANK_BIC        e.g. ALFAMYKL (required)
//	BANK_NAME       display name
//	PORT            HTTP port (default 9000)
//	SWITCH_URL      e.g. http://switch:8080
//	SWITCH_API_KEY  this bank's key at the switch
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/ledger"
	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/server"
	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/switchclient"
)

type customer struct {
	account, name string
	balance       int64 // sen
	mobile, nric  string
}

// Demo customers per bank. Names and numbers are made up.
var seeds = map[string][]customer{
	"ALFAMYKL": {
		{"1100000001", "Aisyah Rahman", 500_000, "0123456701", "900101145671"},
		{"1100000002", "Daniel Tan Wei Ming", 250_000, "0123456702", ""},
		{"1100000003", "Kavitha Nair", 80_000, "", ""},
	},
	"BRVOMYKL": {
		{"2200000001", "Hafiz Ismail", 300_000, "0198765401", "880505105511"},
		{"2200000002", "Mei Ling Wong", 120_000, "0198765402", ""},
	},
	"CHRLMYKL": {
		{"3300000001", "Arjun Kumar", 150_000, "0171112201", ""},
		{"3300000002", "Siti Nurhaliza Omar", 90_000, "0171112202", ""},
	},
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	log := slog.New(slog.NewTextHandler(os.Stdout, nil))
	bic := env("BANK_BIC", "")
	if len(bic) != 8 {
		log.Error("BANK_BIC must be an 8-character BIC")
		os.Exit(1)
	}
	name := env("BANK_NAME", bic)
	port := env("PORT", "9000")

	l := ledger.New()
	for _, c := range seeds[bic] {
		l.Open(c.account, c.name, c.balance)
	}
	sw := switchclient.New(env("SWITCH_URL", "http://localhost:8080"), bic, env("SWITCH_API_KEY", ""))
	srv := server.New(bic, name, l, sw, log.With("bank", bic))

	go registerDemoProxies(log, sw, seeds[bic])

	httpServer := &http.Server{Addr: ":" + port, Handler: srv.Routes(), ReadHeaderTimeout: 5 * time.Second}
	go func() {
		log.Info("bank listening", "bic", bic, "name", name, "port", port)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("server failed", "err", err)
			os.Exit(1)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = httpServer.Shutdown(ctx)
}

// registerDemoProxies links the demo customers' phone and IC numbers to their accounts, retrying until the
// switch is up (the containers start in any order).
func registerDemoProxies(log *slog.Logger, sw *switchclient.Client, customers []customer) {
	var regs []switchclient.ProxyRegistration
	for _, c := range customers {
		if c.mobile != "" {
			regs = append(regs, switchclient.ProxyRegistration{Type: "MOBILE", Value: c.mobile, AccountNumber: c.account, AccountName: c.name})
		}
		if c.nric != "" {
			regs = append(regs, switchclient.ProxyRegistration{Type: "NRIC", Value: c.nric, AccountNumber: c.account, AccountName: c.name})
		}
	}
	for attempt := 1; len(regs) > 0; attempt++ {
		var failed []switchclient.ProxyRegistration
		for _, r := range regs {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			if err := sw.RegisterProxy(ctx, r); err != nil {
				failed = append(failed, r)
			}
			cancel()
		}
		if len(failed) == 0 {
			log.Info("demo proxies registered", "count", len(regs))
			return
		}
		if attempt%10 == 1 {
			log.Info("waiting for the switch to register demo proxies", "pending", len(failed))
		}
		regs = failed
		time.Sleep(3 * time.Second)
	}
}
