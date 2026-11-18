-- The reconciliation job (M4-05) looks at INITIATED intents as well as PAYMENT_PENDING ones: M4-03 leaves an intent
-- INITIATED when Paystack timed out, and it may exist there. V13's index covered pending intents only.
CREATE INDEX ix_payment_intents_unsettled ON payment_intents (created_at)
    WHERE status IN ('INITIATED', 'PAYMENT_PENDING');

DROP INDEX ix_payment_intents_pending;
