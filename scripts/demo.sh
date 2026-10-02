#!/usr/bin/env bash
# End-to-end tour of the switch. Start everything first:  docker compose up -d --build
# Then:  ./scripts/demo.sh        (needs only bash and curl)
set -euo pipefail

SWITCH=${SWITCH:-http://localhost:8080}
ALFA=${ALFA:-http://localhost:9001}
BRAVO=${BRAVO:-http://localhost:9002}
CHARLIE=${CHARLIE:-http://localhost:9003}
ADMIN_KEY=${ITS_ADMIN_KEY:-local-admin-key}

bold() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }   # tiny JSON reader, no jq needed
post() { curl -s -X POST "$1" -H 'Content-Type: application/json' -d "$2"; }
pay() {
  local body; body=$(post "$ALFA/transfers" "$1")
  printf '%s\n' "$body"
  local status; status=$(printf '%s' "$body" | field status)
  local reason; reason=$(printf '%s' "$body" | field reason)
  printf '   -> %s %s\n' "$status" "$reason"
  if [ -n "${2:-}" ] && [ "$status${reason:+ $reason}" != "$2" ]; then echo "   expected: $2" >&2; exit 1; fi
}

bold "Waiting for the switch and the banks"
for _ in $(seq 1 90); do
  if curl -sf "$SWITCH/actuator/health" >/dev/null \
     && curl -sf -H 'X-Participant: ALFAMYKL' -H 'X-Api-Key: alfa-dev-key' "$SWITCH/api/v1/proxies/MOBILE/0171112201" >/dev/null; then
    break
  fi
  sleep 2
done
curl -sf "$SWITCH/actuator/health" >/dev/null || { echo "The switch is not up. Run: docker compose up -d --build" >&2; exit 1; }
echo "ready. Dashboard: $SWITCH"

bold "Starting balances (sen)"
curl -s "$ALFA/accounts/1100000001"; echo
curl -s "$BRAVO/accounts/2200000001"; echo

bold "1. Proxy lookup: who owns mobile 019-876 5401?"
curl -s "$ALFA/lookup/MOBILE/019-8765401"; echo

bold "2. Aisyah (Alfa) pays RM 125.50 to that mobile number"
pay '{"fromAccount":"1100000001","proxyType":"MOBILE","proxyValue":"0198765401","amount":"125.50","reference":"Lunch"}' COMPLETED

bold "3. Pay Charlie Bank by account number"
pay '{"fromAccount":"1100000002","toBic":"CHRLMYKL","toAccount":"3300000001","toName":"Arjun Kumar","amount":"80.00"}' COMPLETED

bold "4. Wrong account number at Bravo: rejected by the receiving bank"
pay '{"fromAccount":"1100000001","toBic":"BRVOMYKL","toAccount":"2299999999","amount":"10.00"}' "REJECTED AC01"

bold "5. Bravo becomes slow (7 s): the switch gives up after 5 s and reverses the late credit"
post "$BRAVO/admin/chaos" '{"delayMs":7000}'; echo
pay '{"fromAccount":"1100000001","proxyType":"MOBILE","proxyValue":"0198765402","amount":"42.00"}' "REJECTED AB05"
post "$BRAVO/admin/chaos" '{"delayMs":0}' >/dev/null
echo "   waiting for the camt.056 cancellation to reach Bravo..."
for _ in $(seq 1 15); do
  if curl -s "$BRAVO/transfers" | grep -q '"status":"REVERSED"'; then echo "   Bravo reversed the late credit"; break; fi
  sleep 1
done

bold "6. Charlie goes offline: rejected at once, no waiting"
post "$CHARLIE/admin/chaos" '{"offline":true}'; echo
sleep 4   # the switch checks every bank's heartbeat every 3 s
pay '{"fromAccount":"1100000001","toBic":"CHRLMYKL","toAccount":"3300000002","amount":"15.00"}' "REJECTED AB08"
post "$CHARLIE/admin/chaos" '{"offline":false}' >/dev/null

bold "7. Bravo pays back, so the nets aren't one-sided"
curl -s -X POST "$BRAVO/transfers" -H 'Content-Type: application/json' \
  -d '{"fromAccount":"2200000001","proxyType":"MOBILE","proxyValue":"0123456701","amount":"30.00"}'; echo

bold "8. End-of-day settlement: close the cycle and net every bank"
report=$(curl -s -X POST "$SWITCH/api/v1/settlement/close" -H "X-Admin-Key: $ADMIN_KEY")
printf '%s\n' "$report"
cycle=$(printf '%s' "$report" | field cycleId)
total=$(printf '%s' "$report" | grep -o '"net":-\{0,1\}[0-9]*' | cut -d: -f2 | awk '{s+=$1} END {print s+0}')
echo "   cycle $cycle closed; sum of nets = $total (must be 0)"
[ "$total" = "0" ] || { echo "settlement does not balance" >&2; exit 1; }

bold "9. Each bank reconciles its own books against the switch"
for bank in "$ALFA" "$BRAVO" "$CHARLIE"; do
  r=$(curl -s "$bank/admin/reconcile/$cycle")
  printf '%s\n' "$r"
  printf '%s' "$r" | grep -q '"reconciled":true' || { echo "reconciliation break" >&2; exit 1; }
done

bold "Done. Open $SWITCH for the dashboard."
