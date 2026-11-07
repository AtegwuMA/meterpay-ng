-- The double-entry ledger (DESIGN 6.2, 6.3). Every kobo that moves is one journal entry with balanced postings.
-- journal_entries and postings have no updated_at or version: they never change, and corrections are new entries.
CREATE TABLE ledger_accounts (
    id           UUID        PRIMARY KEY,
    owner_type   VARCHAR(16) NOT NULL,
    owner_id     UUID        REFERENCES users (id),
    type         VARCHAR(32) NOT NULL,
    currency     VARCHAR(3)  NOT NULL,
    balance_kobo BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    version      BIGINT      NOT NULL,

    -- NULLS NOT DISTINCT: platform accounts have no owner, and a plain UNIQUE treats NULLs as different from each
    -- other, so it would allow any number of VAT_PAYABLE accounts.
    CONSTRAINT uk_ledger_accounts_owner_type UNIQUE NULLS NOT DISTINCT (owner_type, owner_id, type),
    CONSTRAINT ck_ledger_accounts_owner_type CHECK (owner_type IN ('TENANT', 'LANDLORD', 'PLATFORM')),
    CONSTRAINT ck_ledger_accounts_owner_id CHECK ((owner_type = 'PLATFORM') = (owner_id IS NULL)),
    -- Each type belongs to one kind of owner, so a landlord cannot end up with a wallet.
    CONSTRAINT ck_ledger_accounts_type CHECK (
        (owner_type = 'TENANT' AND type = 'TENANT_WALLET')
        OR (owner_type = 'LANDLORD' AND type = 'LANDLORD_REVENUE')
        OR (owner_type = 'PLATFORM' AND type IN ('GATEWAY_CLEARING', 'VAT_PAYABLE', 'PLATFORM_FEE_REVENUE'))
    ),
    CONSTRAINT ck_ledger_accounts_currency CHECK (currency = 'NGN'),
    -- Only wallets have a floor. Balances are positive in the account's normal direction, and a refund can leave
    -- another account below zero for a while, so the check is on this type alone.
    CONSTRAINT ck_ledger_accounts_wallet_not_negative CHECK (type <> 'TENANT_WALLET' OR balance_kobo >= 0)
);

CREATE TABLE journal_entries (
    id              UUID         PRIMARY KEY,
    entry_type      VARCHAR(32)  NOT NULL,
    reference_type  VARCHAR(32),
    reference_id    UUID,
    idempotency_key VARCHAR(200) NOT NULL,
    description     VARCHAR(255),
    posted_at       TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,

    -- This is what makes posting idempotent (M3-04): a retry hits this and returns the existing entry.
    CONSTRAINT uk_journal_entries_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_journal_entries_entry_type CHECK (entry_type IN ('WALLET_TOPUP', 'ENERGY_PURCHASE', 'REFUND', 'ADJUSTMENT')),
    -- A blank key would make every keyless retry look like the same entry.
    CONSTRAINT ck_journal_entries_key_not_blank CHECK (btrim(idempotency_key) <> ''),
    -- A manual adjustment can point at nothing, but half a reference points at nothing and looks as if it does.
    CONSTRAINT ck_journal_entries_reference CHECK ((reference_type IS NULL) = (reference_id IS NULL))
);

CREATE TABLE postings (
    id               UUID        PRIMARY KEY,
    journal_entry_id UUID        NOT NULL REFERENCES journal_entries (id),
    account_id       UUID        NOT NULL REFERENCES ledger_accounts (id),
    direction        VARCHAR(6)  NOT NULL,
    amount_kobo      BIGINT      NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,

    -- Direction lives in its own column, so an amount is never negative and a zero line is not a posting.
    CONSTRAINT ck_postings_amount CHECK (amount_kobo > 0),
    CONSTRAINT ck_postings_direction CHECK (direction IN ('DEBIT', 'CREDIT'))
);

-- An entry's postings, and an account's history (M3-06).
CREATE INDEX ix_postings_journal_entry_id ON postings (journal_entry_id);
CREATE INDEX ix_postings_account_id ON postings (account_id);
