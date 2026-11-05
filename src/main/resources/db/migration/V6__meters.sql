-- Smart sub-meters (DESIGN 6.2). landlord_id is an addition to DESIGN: unit_id is empty until a meter is
-- assigned, and a registered meter still has to belong to someone.
CREATE TABLE meters (
    id                      UUID        PRIMARY KEY,
    landlord_id             UUID        NOT NULL REFERENCES users (id),
    unit_id                 UUID        REFERENCES units (id),
    serial_number           VARCHAR(40) NOT NULL,
    gateway_type            VARCHAR(16) NOT NULL,
    external_device_id      VARCHAR(100),
    status                  VARCHAR(20) NOT NULL,
    last_register_wh        BIGINT      NOT NULL,
    last_reading_at         TIMESTAMPTZ,
    credit_balance_wh       BIGINT      NOT NULL,
    low_credit_threshold_wh BIGINT      NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    version                 BIGINT      NOT NULL,

    -- UNIQUE allows many NULLs, so any number of meters can wait unassigned while each unit has at most one.
    CONSTRAINT uk_meters_unit_id UNIQUE (unit_id),
    CONSTRAINT uk_meters_serial_number UNIQUE (serial_number),
    CONSTRAINT ck_meters_serial_upper CHECK (serial_number = upper(serial_number)),
    CONSTRAINT ck_meters_gateway_type CHECK (gateway_type IN ('SIMULATED', 'TUYA')),
    CONSTRAINT ck_meters_tuya_device CHECK (gateway_type <> 'TUYA' OR external_device_id IS NOT NULL),
    CONSTRAINT ck_meters_status CHECK (status IN ('PROVISIONED', 'ACTIVE', 'LOW_CREDIT', 'CUTOFF',
        'TAMPER_SUSPECTED', 'SUSPENDED', 'DECOMMISSIONED')),
    CONSTRAINT ck_meters_register CHECK (last_register_wh >= 0),
    CONSTRAINT ck_meters_threshold CHECK (low_credit_threshold_wh >= 0)
    -- credit_balance_wh has no sign check on purpose: credit can overshoot below zero before the cutoff (DESIGN 7.2).
);

CREATE INDEX ix_meters_landlord_id ON meters (landlord_id);
