-- Opaque refresh tokens, stored only as a SHA-256 hash (DESIGN 6.2, 11). Every token belongs to a family: the
-- chain of rotations that started at one login. Reusing a rotated token revokes the whole family.
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id),
    token_hash  VARCHAR(64) NOT NULL,
    family_id   UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID        REFERENCES refresh_tokens (id),
    updated_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL,

    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > created_at),
    -- A token that was rotated is always revoked at the same time; a replaced but live token cannot exist.
    CONSTRAINT ck_refresh_tokens_replaced_means_revoked CHECK (replaced_by IS NULL OR revoked_at IS NOT NULL)
);

-- Revoking a family and logging a user out both search by these columns.
CREATE INDEX ix_refresh_tokens_family_id ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
