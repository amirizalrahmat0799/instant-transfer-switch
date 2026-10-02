-- Settlement cycles: transfers are grouped into cycles; at the cut-over each bank's net position is settled.
CREATE TABLE settlement_cycles (
    id         BIGSERIAL PRIMARY KEY,
    status     TEXT        NOT NULL CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED')),
    opened_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    closing_at TIMESTAMPTZ,
    closed_at  TIMESTAMPTZ
);
-- At most one open cycle at any time
CREATE UNIQUE INDEX settlement_cycles_one_open ON settlement_cycles (status) WHERE status = 'OPEN';

-- Running totals per bank per cycle. "sent" is reserved when a transfer is forwarded (and released if it fails),
-- so the net debit cap check sees money that is in flight.
CREATE TABLE positions (
    cycle_id        BIGINT NOT NULL REFERENCES settlement_cycles (id),
    bic             TEXT   NOT NULL,
    sent_count      BIGINT NOT NULL DEFAULT 0,
    sent_amount     BIGINT NOT NULL DEFAULT 0,
    received_count  BIGINT NOT NULL DEFAULT 0,
    received_amount BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (cycle_id, bic)
);

CREATE TABLE settlement_reports (
    cycle_id        BIGINT NOT NULL REFERENCES settlement_cycles (id),
    bic             TEXT   NOT NULL,
    sent_count      BIGINT NOT NULL,
    sent_amount     BIGINT NOT NULL,
    received_count  BIGINT NOT NULL,
    received_amount BIGINT NOT NULL,
    net_amount      BIGINT NOT NULL,
    PRIMARY KEY (cycle_id, bic)
);

-- Proxy registry: phone / IC / business number -> bank and account (DuitNow-style addressing)
CREATE TABLE proxies (
    proxy_type     TEXT        NOT NULL CHECK (proxy_type IN ('MOBILE', 'NRIC', 'BUSINESS')),
    proxy_value    TEXT        NOT NULL,
    bic            TEXT        NOT NULL,
    account_number TEXT        NOT NULL,
    account_name   TEXT        NOT NULL,
    registered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (proxy_type, proxy_value)
);

CREATE TABLE transfers (
    id               UUID PRIMARY KEY,
    msg_id           TEXT        NOT NULL,
    end_to_end_id    TEXT        NOT NULL,
    tx_id            TEXT        NOT NULL,
    debtor_bic       TEXT        NOT NULL,
    creditor_bic     TEXT        NOT NULL,
    debtor_account   TEXT        NOT NULL,
    debtor_name      TEXT,
    creditor_account TEXT        NOT NULL,
    creditor_name    TEXT,
    amount           BIGINT      NOT NULL CHECK (amount > 0),
    remittance       TEXT,
    status           TEXT        NOT NULL CHECK (status IN ('FORWARDED', 'COMPLETED', 'REJECTED', 'TIMED_OUT')),
    reason_code      TEXT,
    reason_info      TEXT,
    cycle_id         BIGINT REFERENCES settlement_cycles (id),
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    latency_ms       INT,
    response_xml     TEXT,
    -- A bank's end-to-end id identifies its transfer: retries are recognised and answered with the first result
    CONSTRAINT transfers_e2e_unique UNIQUE (debtor_bic, end_to_end_id)
);
CREATE INDEX transfers_cycle_status ON transfers (cycle_id, status);
CREATE INDEX transfers_received_at ON transfers (received_at DESC);

-- Cancellations (camt.056) owed to receiving banks for transfers the switch timed out on
CREATE TABLE reversals (
    transfer_id     UUID PRIMARY KEY REFERENCES transfers (id),
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'CANCELLED', 'NOTHING_TO_CANCEL', 'FAILED')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    resolved_at     TIMESTAMPTZ
);
CREATE INDEX reversals_due ON reversals (next_attempt_at) WHERE status = 'PENDING';
