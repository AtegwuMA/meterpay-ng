package com.theodomart.energy.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.IntegrationTest;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database's own rules, written around any Java so they hold even if the code has a bug. Each test rolls back: a
 * platform account left behind would be counted by every later test that adds up the ledger. In Postgres a failed
 * statement ends its transaction, so each test can only assert one rejection, and it must be the last thing it does.
 */
@Transactional
class LedgerSchemaIT extends IntegrationTest {

	private final JdbcTemplate jdbc;

	@Autowired
	LedgerSchemaIT(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Test
	void walletCanHoldZeroAndMore() {
		UUID tenant = newUser("TENANT");

		account("TENANT", tenant, "TENANT_WALLET", 0);

		assertThat(jdbc.queryForObject("SELECT balance_kobo FROM ledger_accounts WHERE owner_id = ?", Long.class, tenant))
			.isZero();
	}

	@Test
	void walletCannotGoBelowZero() {
		UUID wallet = account("TENANT", newUser("TENANT"), "TENANT_WALLET", 100);

		assertRejected("ck_ledger_accounts_wallet_not_negative",
				() -> jdbc.update("UPDATE ledger_accounts SET balance_kobo = -1 WHERE id = ?", wallet));
	}

	@Test
	void otherAccountsCanGoBelowZero() {
		UUID revenue = account("LANDLORD", newUser("LANDLORD"), "LANDLORD_REVENUE", 100);

		assertThat(jdbc.update("UPDATE ledger_accounts SET balance_kobo = -1 WHERE id = ?", revenue)).isOne();
	}

	@Test
	void aTenantHasOneWallet() {
		UUID tenant = newUser("TENANT");
		account("TENANT", tenant, "TENANT_WALLET", 0);

		assertRejected("uk_ledger_accounts_owner_type", () -> account("TENANT", tenant, "TENANT_WALLET", 0));
	}

	@Test
	void differentTenantsEachHaveAWallet() {
		account("TENANT", newUser("TENANT"), "TENANT_WALLET", 0);

		assertThat(account("TENANT", newUser("TENANT"), "TENANT_WALLET", 0)).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "GATEWAY_CLEARING", "VAT_PAYABLE", "PLATFORM_FEE_REVENUE" })
	void thePlatformHasOneAccountOfEachType(String type) {
		// Added only if absent, so this still works once M3-02 seeds these accounts by migration.
		platformAccountIfAbsent(type);

		assertRejected("uk_ledger_accounts_owner_type", () -> account("PLATFORM", null, type, 0));
	}

	@Test
	void onConflictRecognisesAPlatformAccountThatAlreadyExists() {
		platformAccountIfAbsent("VAT_PAYABLE");

		// The backfill and seeding in M3-02 rely on this: with a plain UNIQUE this would insert a second row.
		assertThat(platformAccountIfAbsent("VAT_PAYABLE")).isZero();
	}

	@ParameterizedTest
	@CsvSource({ "TENANT, LANDLORD_REVENUE", "TENANT, VAT_PAYABLE", "LANDLORD, TENANT_WALLET", "LANDLORD, GATEWAY_CLEARING" })
	void accountTypeMustSuitItsOwner(String ownerType, String type) {
		UUID owner = newUser(ownerType);

		assertRejected("ck_ledger_accounts_type", () -> account(ownerType, owner, type, 0));
	}

	@ParameterizedTest
	@ValueSource(strings = { "TENANT_WALLET", "LANDLORD_REVENUE" })
	void platformCannotHoldAPersonsAccount(String type) {
		assertRejected("ck_ledger_accounts_type", () -> account("PLATFORM", null, type, 0));
	}

	@Test
	void unknownAccountTypeIsRefused() {
		assertRejected("ck_ledger_accounts_type", () -> account("PLATFORM", null, "SUSPENSE", 0));
	}

	@Test
	void unknownOwnerTypeIsRefused() {
		UUID someone = newUser("TENANT");

		assertRejected("ck_ledger_accounts_owner_type", () -> account("BANK", someone, "VAT_PAYABLE", 0));
	}

	@Test
	void aPersonsAccountNeedsAnOwner() {
		assertRejected("ck_ledger_accounts_owner_id", () -> account("TENANT", null, "TENANT_WALLET", 0));
	}

	@Test
	void aPlatformAccountHasNoOwner() {
		UUID someone = newUser("TENANT");

		assertRejected("ck_ledger_accounts_owner_id", () -> account("PLATFORM", someone, "VAT_PAYABLE", 0));
	}

	@Test
	void ownerMustBeARealUser() {
		assertRejected("ledger_accounts_owner_id_fkey",
				() -> account("TENANT", UUID.randomUUID(), "TENANT_WALLET", 0));
	}

	@Test
	void onlyNairaIsAllowed() {
		UUID tenant = newUser("TENANT");

		assertRejected("ck_ledger_accounts_currency", () -> jdbc.update("""
				INSERT INTO ledger_accounts (id, owner_type, owner_id, type, currency, balance_kobo, created_at, updated_at, version)
				VALUES (?, 'TENANT', ?, 'TENANT_WALLET', 'USD', 0, now(), now(), 0)
				""", UUID.randomUUID(), tenant));
	}

	@Test
	void anIdempotencyKeyCanOnlyBeUsedOnce() {
		String key = "key-" + UUID.randomUUID();
		entry("WALLET_TOPUP", key);

		assertRejected("uk_journal_entries_idempotency_key", () -> entry("REFUND", key));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void aBlankIdempotencyKeyIsRefused(String key) {
		assertRejected("ck_journal_entries_key_not_blank", () -> entry("WALLET_TOPUP", key));
	}

	@Test
	void unknownEntryTypeIsRefused() {
		assertRejected("ck_journal_entries_entry_type", () -> entry("GIFT", "key-" + UUID.randomUUID()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "ENERGY_PURCHASE", "WALLET_TOPUP", "REFUND", "ADJUSTMENT" })
	void everyEntryTypeInTheDesignIsAllowed(String type) {
		assertThat(entry(type, "key-" + UUID.randomUUID())).isNotNull();
	}

	@Test
	void aReferenceNeedsBothItsTypeAndItsId() {
		assertRejected("ck_journal_entries_reference", () -> jdbc.update("""
				INSERT INTO journal_entries (id, entry_type, reference_type, idempotency_key, posted_at, created_at)
				VALUES (?, 'ADJUSTMENT', 'PAYMENT_INTENT', ?, now(), now())
				""", UUID.randomUUID(), "key-" + UUID.randomUUID()));
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, -1 })
	void aPostingMustBeForAPositiveAmount(long amount) {
		UUID entry = entry("WALLET_TOPUP", "key-" + UUID.randomUUID());
		UUID wallet = account("TENANT", newUser("TENANT"), "TENANT_WALLET", 0);

		assertRejected("ck_postings_amount", () -> posting(entry, wallet, "CREDIT", amount));
	}

	@Test
	void aPostingIsADebitOrACredit() {
		UUID entry = entry("WALLET_TOPUP", "key-" + UUID.randomUUID());
		UUID wallet = account("TENANT", newUser("TENANT"), "TENANT_WALLET", 0);

		assertRejected("ck_postings_direction", () -> posting(entry, wallet, "BOTH", 100));
	}

	@Test
	void aPostingNeedsARealEntry() {
		UUID wallet = account("TENANT", newUser("TENANT"), "TENANT_WALLET", 0);

		assertRejected("postings_journal_entry_id_fkey", () -> posting(UUID.randomUUID(), wallet, "CREDIT", 100));
	}

	@Test
	void aPostingNeedsARealAccount() {
		UUID entry = entry("WALLET_TOPUP", "key-" + UUID.randomUUID());

		assertRejected("postings_account_id_fkey", () -> posting(entry, UUID.randomUUID(), "CREDIT", 100));
	}

	@Test
	void anEntryWithPostingsCannotBeDeleted() {
		UUID entry = entry("WALLET_TOPUP", "key-" + UUID.randomUUID());
		posting(entry, account("TENANT", newUser("TENANT"), "TENANT_WALLET", 0), "CREDIT", 100);

		assertRejected("postings_journal_entry_id_fkey",
				() -> jdbc.update("DELETE FROM journal_entries WHERE id = ?", entry));
	}

	@Test
	void anAccountWithPostingsCannotBeDeleted() {
		UUID wallet = account("TENANT", newUser("TENANT"), "TENANT_WALLET", 0);
		posting(entry("WALLET_TOPUP", "key-" + UUID.randomUUID()), wallet, "CREDIT", 100);

		assertRejected("postings_account_id_fkey", () -> jdbc.update("DELETE FROM ledger_accounts WHERE id = ?", wallet));
	}

	@Test
	void aUserWithAnAccountCannotBeDeleted() {
		UUID tenant = newUser("TENANT");
		account("TENANT", tenant, "TENANT_WALLET", 0);

		assertRejected("ledger_accounts_owner_id_fkey", () -> jdbc.update("DELETE FROM users WHERE id = ?", tenant));
	}

	@Test
	void theDesignsTopUpRecipeFits() {
		UUID entry = entry("WALLET_TOPUP", "key-" + UUID.randomUUID());
		UUID wallet = account("TENANT", newUser("TENANT"), "TENANT_WALLET", 500_000);
		platformAccountIfAbsent("GATEWAY_CLEARING");
		UUID clearing = jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE type = 'GATEWAY_CLEARING'", UUID.class);

		posting(entry, clearing, "DEBIT", 500_000);
		posting(entry, wallet, "CREDIT", 500_000);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM postings WHERE journal_entry_id = ?", Integer.class, entry))
			.isEqualTo(2);
	}

	private UUID newUser(String role) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO users (id, email, phone, password_hash, full_name, role, status, created_at, updated_at, version)
				VALUES (?, ?, ?, 'not-a-real-hash', 'Ledger Test', ?, 'ACTIVE', now(), now(), 0)
				""", id, "user-" + id + "@example.com", "+234803" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000),
				role);
		return id;
	}

	private UUID account(String ownerType, UUID ownerId, String type, long balance) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO ledger_accounts (id, owner_type, owner_id, type, currency, balance_kobo, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, 'NGN', ?, now(), now(), 0)
				""", id, ownerType, ownerId, type, balance);
		return id;
	}

	private int platformAccountIfAbsent(String type) {
		return jdbc.update("""
				INSERT INTO ledger_accounts (id, owner_type, owner_id, type, currency, balance_kobo, created_at, updated_at, version)
				VALUES (?, 'PLATFORM', NULL, ?, 'NGN', 0, now(), now(), 0)
				ON CONFLICT (owner_type, owner_id, type) DO NOTHING
				""", UUID.randomUUID(), type);
	}

	private UUID entry(String type, String key) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO journal_entries (id, entry_type, idempotency_key, posted_at, created_at)
				VALUES (?, ?, ?, now(), now())
				""", id, type, key);
		return id;
	}

	private void posting(UUID entry, UUID account, String direction, long amount) {
		jdbc.update("""
				INSERT INTO postings (id, journal_entry_id, account_id, direction, amount_kobo, created_at)
				VALUES (?, ?, ?, ?, ?, now())
				""", UUID.randomUUID(), entry, account, direction, amount);
	}

	private static void assertRejected(String constraint, Runnable statement) {
		assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(constraint);
	}

}
