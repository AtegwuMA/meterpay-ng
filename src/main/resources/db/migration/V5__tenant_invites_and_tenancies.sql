-- One-time codes a landlord hands to a new tenant. Not in DESIGN 6.2, which names the endpoint but no table.
CREATE TABLE tenant_invites (
    id          UUID        PRIMARY KEY,
    unit_id     UUID        NOT NULL REFERENCES units (id),
    code_hash   VARCHAR(64) NOT NULL,
    created_by  UUID        NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    accepted_by UUID        REFERENCES users (id),
    revoked_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL,

    CONSTRAINT uk_tenant_invites_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_tenant_invites_hash_hex CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tenant_invites_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_tenant_invites_accepted CHECK ((accepted_at IS NULL) = (accepted_by IS NULL)),
    CONSTRAINT ck_tenant_invites_not_both CHECK (accepted_at IS NULL OR revoked_at IS NULL)
);

-- At most one code in circulation per unit, so a landlord always knows which one works.
CREATE UNIQUE INDEX uk_tenant_invites_one_open_per_unit ON tenant_invites (unit_id)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

-- A tenant's assignment to a unit (DESIGN 6.2). Ended rows stay as history.
CREATE TABLE tenancies (
    id         UUID        PRIMARY KEY,
    unit_id    UUID        NOT NULL REFERENCES units (id),
    tenant_id  UUID        NOT NULL REFERENCES users (id),
    starts_on  DATE        NOT NULL,
    ends_on    DATE,
    status     VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL,

    CONSTRAINT ck_tenancies_status CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_tenancies_ended_has_date CHECK ((status = 'ENDED') = (ends_on IS NOT NULL)),
    CONSTRAINT ck_tenancies_dates CHECK (ends_on IS NULL OR ends_on >= starts_on)
);

-- DESIGN: one ACTIVE tenancy per unit. Partial, so any number of ENDED rows can sit beside it.
CREATE UNIQUE INDEX uk_tenancies_one_active_per_unit ON tenancies (unit_id) WHERE status = 'ACTIVE';

CREATE INDEX ix_tenancies_tenant_id ON tenancies (tenant_id);
