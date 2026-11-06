-- What a suspended meter returns to on resume (DESIGN 7.2). The two CHECKs mean a meter is suspended exactly
-- when this is set, and it can only hold a state that a meter may be suspended from.
ALTER TABLE meters
    ADD COLUMN status_before_suspension VARCHAR(20),
    ADD CONSTRAINT ck_meters_suspension_memory
        CHECK ((status = 'SUSPENDED') = (status_before_suspension IS NOT NULL)),
    ADD CONSTRAINT ck_meters_status_before_suspension
        CHECK (status_before_suspension IN ('PROVISIONED', 'ACTIVE', 'LOW_CREDIT', 'CUTOFF', 'TAMPER_SUSPECTED'));
