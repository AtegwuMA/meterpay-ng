-- What a building charges for electricity (DESIGN 6.2). A purchase only knows its meter's building, so each
-- building has one plan. Neither table has updated_at or version: rows here never change.
CREATE TABLE tariff_plans (
    id          UUID         PRIMARY KEY,
    building_id UUID         NOT NULL REFERENCES buildings (id),
    name        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uk_tariff_plans_building_id UNIQUE (building_id)
);

CREATE TABLE tariff_versions (
    id                        UUID        PRIMARY KEY,
    plan_id                   UUID        NOT NULL REFERENCES tariff_plans (id),
    rate_kobo_per_kwh         BIGINT      NOT NULL,
    vat_basis_points          INTEGER     NOT NULL,
    platform_fee_basis_points INTEGER     NOT NULL,
    effective_from            TIMESTAMPTZ NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL,

    -- Two versions starting together would make "the active version" ambiguous. This index also serves the
    -- active version lookup: the newest effective_from at or before now, for one plan.
    CONSTRAINT uk_tariff_versions_plan_effective_from UNIQUE (plan_id, effective_from),
    -- The energy calculation divides by the rate.
    CONSTRAINT ck_tariff_versions_rate CHECK (rate_kobo_per_kwh > 0),
    CONSTRAINT ck_tariff_versions_vat CHECK (vat_basis_points BETWEEN 0 AND 10000),
    -- Below 100%: at exactly 100% the landlord receives nothing and the tenant gets no energy.
    CONSTRAINT ck_tariff_versions_fee CHECK (platform_fee_basis_points BETWEEN 0 AND 9999)
);
