-- The platform's own accounts (DESIGN 6.3), and the accounts of everyone who registered before the ledger existed.
-- Registration opens accounts from M3-02 on. Every statement is safe to run again: ON CONFLICT relies on the
-- NULLS NOT DISTINCT unique constraint from V10, which also treats the platform's NULL owner as one owner.
INSERT INTO ledger_accounts (id, owner_type, owner_id, type, currency, balance_kobo, created_at, updated_at, version)
SELECT gen_random_uuid(), 'PLATFORM', NULL, platform.type, 'NGN', 0, now(), now(), 0
FROM (VALUES ('GATEWAY_CLEARING'), ('VAT_PAYABLE'), ('PLATFORM_FEE_REVENUE')) AS platform (type)
ON CONFLICT (owner_type, owner_id, type) DO NOTHING;

-- Disabled users are included: a disabled tenant may still have money that the ledger has to account for.
INSERT INTO ledger_accounts (id, owner_type, owner_id, type, currency, balance_kobo, created_at, updated_at, version)
SELECT gen_random_uuid(), 'TENANT', users.id, 'TENANT_WALLET', 'NGN', 0, now(), now(), 0
FROM users
WHERE users.role = 'TENANT'
ON CONFLICT (owner_type, owner_id, type) DO NOTHING;

INSERT INTO ledger_accounts (id, owner_type, owner_id, type, currency, balance_kobo, created_at, updated_at, version)
SELECT gen_random_uuid(), 'LANDLORD', users.id, 'LANDLORD_REVENUE', 'NGN', 0, now(), now(), 0
FROM users
WHERE users.role = 'LANDLORD'
ON CONFLICT (owner_type, owner_id, type) DO NOTHING;
