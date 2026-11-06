-- VAT is a legal rate that applies to every building, not part of a landlord's price (DESIGN 6.2). A law change is
-- one new row with a future effective_from, added by a migration. Rows here never change.
CREATE TABLE tax_rates (
    id               UUID        PRIMARY KEY,
    vat_basis_points INTEGER     NOT NULL,
    effective_from   TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,

    -- Two rates starting together would make "the active rate" ambiguous. This index also serves the lookup.
    CONSTRAINT uk_tax_rates_effective_from UNIQUE (effective_from),
    CONSTRAINT ck_tax_rates_vat CHECK (vat_basis_points BETWEEN 0 AND 10000)
);

-- A purchase cannot be priced without a rate, so there must always be one in force.
INSERT INTO tax_rates (id, vat_basis_points, effective_from, created_at)
VALUES (gen_random_uuid(), 750, TIMESTAMPTZ '2020-02-01 00:00:00+01', now());

-- No purchase has been priced from this column: M5-03, the first code that prices one, is not built yet.
ALTER TABLE tariff_versions DROP COLUMN vat_basis_points;
