-- Transactional outbox (DESIGN 6.2, 8.4, M5-01). A domain event is a row written in the same transaction as the
-- change it describes, so it exists exactly when the change committed. A relay publishes it to Kafka afterwards.
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    -- Copied into the Kafka header X-Correlation-Id, so one request can be followed through every consumer.
    correlation_id VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    version        BIGINT       NOT NULL,

    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_events_published CHECK (published_at IS NULL OR published_at >= created_at)
);

-- The relay only ever looks for unpublished rows, oldest first. Published rows drop out of the index.
CREATE INDEX ix_outbox_events_unpublished ON outbox_events (created_at, id) WHERE published_at IS NULL;
