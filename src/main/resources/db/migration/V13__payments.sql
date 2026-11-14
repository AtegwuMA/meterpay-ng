-- Wallet top-ups through a payment gateway (DESIGN 6.2, 7.1, 8.1).

-- One attempt to put money into a wallet. Created and committed before the gateway is called, so a crash mid-call
-- leaves a row the reconciliation job can find.
CREATE TABLE payment_intents (
    id                UUID          PRIMARY KEY,
    tenant_id         UUID          NOT NULL REFERENCES users (id),
    amount_kobo       BIGINT        NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    -- SHA-256 hex of the tenant and the client's Idempotency-Key header, so two tenants can never collide.
    idempotency_key   VARCHAR(64)   NOT NULL,
    gateway           VARCHAR(20)   NOT NULL,
    gateway_reference VARCHAR(100),
    authorization_url VARCHAR(2048),
    failure_reason    VARCHAR(255),
    paid_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    version           BIGINT        NOT NULL,

    CONSTRAINT uk_payment_intents_idempotency_key UNIQUE (idempotency_key),
    -- UNIQUE allows many NULLs: an intent has no gateway reference until the gateway accepts it.
    CONSTRAINT uk_payment_intents_gateway_reference UNIQUE (gateway_reference),
    CONSTRAINT ck_payment_intents_amount CHECK (amount_kobo > 0),
    CONSTRAINT ck_payment_intents_status CHECK (status IN ('INITIATED', 'PAYMENT_PENDING', 'PAID', 'FAILED', 'REFUNDED')),
    CONSTRAINT ck_payment_intents_key_hex CHECK (idempotency_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payment_intents_gateway CHECK (gateway IN ('PAYSTACK')),
    -- The state and the fields that come with it cannot disagree, whatever code wrote the row.
    CONSTRAINT ck_payment_intents_paid_at CHECK ((paid_at IS NOT NULL) = (status IN ('PAID', 'REFUNDED'))),
    CONSTRAINT ck_payment_intents_failure CHECK ((failure_reason IS NOT NULL) = (status = 'FAILED')),
    CONSTRAINT ck_payment_intents_authorization CHECK (
        status NOT IN ('PAYMENT_PENDING', 'PAID', 'REFUNDED') OR authorization_url IS NOT NULL)
);

-- The reconciliation job (M4-05) looks only for pending intents, oldest first.
CREATE INDEX ix_payment_intents_pending ON payment_intents (created_at) WHERE status = 'PAYMENT_PENDING';
CREATE INDEX ix_payment_intents_tenant_id ON payment_intents (tenant_id, created_at);

-- Every exchange with the gateway, in and out, kept as it happened (M4-07). intent_id is empty for a message that
-- could not be matched to an intent, which is exactly the kind worth keeping.
CREATE TABLE payment_events (
    id             UUID        PRIMARY KEY,
    intent_id      UUID        REFERENCES payment_intents (id),
    event_type     VARCHAR(40) NOT NULL,
    payload        JSONB       NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_payment_events_intent_id ON payment_events (intent_id);

-- A log is only evidence if it cannot be edited: the same rule as the ledger (V12), for the same reason.
CREATE FUNCTION refuse_log_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'append_only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER tr_payment_events_append_only
    BEFORE UPDATE OR DELETE ON payment_events
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_log_change();

-- Every webhook that arrives, signed correctly or not.
CREATE TABLE webhook_receipts (
    id              UUID         PRIMARY KEY,
    gateway         VARCHAR(20)  NOT NULL,
    event_id        VARCHAR(200) NOT NULL,
    signature_valid BOOLEAN      NOT NULL,
    payload         JSONB        NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL,
    processed_at    TIMESTAMPTZ,

    CONSTRAINT ck_webhook_receipts_gateway CHECK (gateway IN ('PAYSTACK')),
    -- Nothing from a request with a bad signature may ever be processed.
    CONSTRAINT ck_webhook_receipts_processed CHECK (processed_at IS NULL OR signature_valid)
);

-- The dedupe for webhooks: one receipt per gateway event. Only among correctly signed ones, or anyone could post a
-- forged webhook carrying a real event id ahead of the gateway and make the genuine one look like a repeat.
CREATE UNIQUE INDEX uk_webhook_receipts_event ON webhook_receipts (gateway, event_id) WHERE signature_valid;
