-- Properties a landlord manages, and the flats or rooms inside them (DESIGN 6.2). Both are mutable.
CREATE TABLE buildings (
    id          UUID         PRIMARY KEY,
    landlord_id UUID         NOT NULL REFERENCES users (id),
    name        VARCHAR(120) NOT NULL,
    address     VARCHAR(300) NOT NULL,
    state       VARCHAR(60)  NOT NULL,
    lga         VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL
);

-- Every building query is "this landlord's buildings", so landlord_id leads the index.
CREATE INDEX ix_buildings_landlord_id_name ON buildings (landlord_id, name);

CREATE TABLE units (
    id          UUID        PRIMARY KEY,
    building_id UUID        NOT NULL REFERENCES buildings (id),
    label       VARCHAR(50) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL
);

-- DESIGN: UNIQUE(building_id, label). On lower(label) so "Flat 3B" and "flat 3b" cannot both exist in one
-- building: tenants and meters are matched to units by label, and two near-identical ones would be confusing.
-- It also serves "units of this building, by label" queries.
CREATE UNIQUE INDEX uk_units_building_label ON units (building_id, lower(label));
