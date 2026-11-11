package com.theodomart.energy.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.common.error.IdempotencyConflictException;
import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.EntryType;
import com.theodomart.energy.ledger.service.InsufficientWalletBalanceException;
import com.theodomart.energy.ledger.service.JournalEntryCommand;
import com.theodomart.energy.ledger.service.JournalEntryView;
import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.PostingLine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The posting service against a real database: the DESIGN 6.4 recipes, the wallet floor and the stored invariants. */
class PostingIT extends IntegrationTest {

	private final LedgerService ledger;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private UUID tenant;

	private UUID landlord;

	@Autowired
	PostingIT(LedgerService ledger, JdbcTemplate jdbc, TransactionTemplate transaction) {
		this.ledger = ledger;
		this.jdbc = jdbc;
		this.transaction = transaction;
	}

	@BeforeEach
	void tenantAndLandlordWithAccounts() {
		tenant = user("TENANT");
		landlord = user("LANDLORD");
		transaction.executeWithoutResult(status -> {
			ledger.openTenantWallet(tenant);
			ledger.openLandlordRevenueAccount(landlord);
		});
	}

	@Test
	void aTopUpMovesMoneyFromClearingIntoTheWallet() {
		long clearingBefore = platformBalance("GATEWAY_CLEARING");

		JournalEntryView entry = ledger.post(topUp(500_000));

		assertThat(balanceOf(tenant)).isEqualTo(500_000);
		assertThat(platformBalance("GATEWAY_CLEARING")).isEqualTo(clearingBefore + 500_000);
		assertThat(postingsOf(entry.id())).containsExactlyInAnyOrder(Map.of("direction", "DEBIT", "amount_kobo", 500_000L),
				Map.of("direction", "CREDIT", "amount_kobo", 500_000L));
	}

	@Test
	void aPurchaseSplitsExactlyAsTheDesignExample() {
		ledger.post(topUp(500_000));
		long vatBefore = platformBalance("VAT_PAYABLE");
		long feeBefore = platformBalance("PLATFORM_FEE_REVENUE");

		JournalEntryView entry = ledger.post(purchase(200_000, 13_953, 3_720, 182_327));

		assertThat(balanceOf(tenant)).isEqualTo(300_000);
		assertThat(balanceOf(landlord)).isEqualTo(182_327);
		assertThat(platformBalance("VAT_PAYABLE")).isEqualTo(vatBefore + 13_953);
		assertThat(platformBalance("PLATFORM_FEE_REVENUE")).isEqualTo(feeBefore + 3_720);
		assertThat(postingsOf(entry.id())).hasSize(4);
		assertThat(jdbc.queryForObject("SELECT entry_type FROM journal_entries WHERE id = ?", String.class, entry.id()))
			.isEqualTo("ENERGY_PURCHASE");
	}

	@Test
	void aWalletCanBeSpentToExactlyZero() {
		ledger.post(topUp(200_000));

		ledger.post(purchase(200_000, 13_953, 3_720, 182_327));

		assertThat(balanceOf(tenant)).isZero();
	}

	@Test
	void aShortWalletLeavesNoTraceAtAll() {
		ledger.post(topUp(120_000));
		long vatBefore = platformBalance("VAT_PAYABLE");
		JournalEntryCommand purchase = purchase(200_000, 13_953, 3_720, 182_327);

		assertThatThrownBy(() -> ledger.post(purchase)).isInstanceOf(InsufficientWalletBalanceException.class)
			.hasMessage("Wallet balance is ₦1,200.00 but ₦2,000.00 is needed.");

		assertThat(balanceOf(tenant)).isEqualTo(120_000);
		assertThat(balanceOf(landlord)).isZero();
		assertThat(platformBalance("VAT_PAYABLE")).isEqualTo(vatBefore);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE idempotency_key = ?",
				Integer.class, purchase.idempotencyKey()))
			.isZero();
	}

	@Test
	void anUnbalancedEntryIsRefused() {
		JournalEntryCommand unbalanced = new JournalEntryCommand(EntryType.ADJUSTMENT, key(), null, null, null,
				List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(100)),
						PostingLine.credit(AccountRef.tenantWallet(tenant), Money.ofKobo(99))));

		assertThatThrownBy(() -> ledger.post(unbalanced)).isInstanceOf(IllegalArgumentException.class);
		assertThat(balanceOf(tenant)).isZero();
	}

	@Test
	void postingTheSameEntryAgainReturnsTheFirstAndMovesNothing() {
		JournalEntryCommand topUp = topUp(500_000);
		JournalEntryView first = ledger.post(topUp);

		JournalEntryView again = ledger.post(topUp);

		assertThat(again.id()).isEqualTo(first.id());
		assertThat(balanceOf(tenant)).isEqualTo(500_000);
		assertThat(entriesWithKey(topUp.idempotencyKey())).isOne();
	}

	@Test
	void theLinesMayComeInAnyOrderOnARetry() {
		JournalEntryCommand topUp = topUp(500_000);
		ledger.post(topUp);
		JournalEntryCommand reordered = new JournalEntryCommand(topUp.type(), topUp.idempotencyKey(),
				topUp.referenceType(), topUp.referenceId(), "Described differently", topUp.lines().reversed());

		ledger.post(reordered);

		assertThat(balanceOf(tenant)).isEqualTo(500_000);
	}

	@Test
	void theSameKeyForADifferentAmountIsRefusedAndChangesNothing() {
		JournalEntryCommand topUp = topUp(500_000);
		ledger.post(topUp);
		JournalEntryCommand bigger = new JournalEntryCommand(topUp.type(), topUp.idempotencyKey(), topUp.referenceType(),
				topUp.referenceId(), null, List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(900_000)),
						PostingLine.credit(AccountRef.tenantWallet(tenant), Money.ofKobo(900_000))));

		assertThatThrownBy(() -> ledger.post(bigger)).isInstanceOf(IdempotencyConflictException.class)
			.hasMessageContaining("different request");
		assertThat(balanceOf(tenant)).isEqualTo(500_000);
	}

	@Test
	void aRepeatInsideACallersTransactionLeavesItUsable() {
		JournalEntryCommand topUp = topUp(500_000);

		// Catching a unique violation here would have aborted this transaction, and the purchase around it with it.
		Long balance = transaction.execute(status -> {
			ledger.post(topUp);
			ledger.post(topUp);
			return balanceOf(tenant);
		});

		assertThat(balance).isEqualTo(500_000);
		assertThat(balanceOf(tenant)).isEqualTo(500_000);
	}

	@Test
	void concurrentRetriesOfOneEntryPostItOnce() throws Exception {
		JournalEntryCommand topUp = topUp(500_000);
		int threads = 8;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<JournalEntryView>> results = new ArrayList<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return ledger.post(topUp);
				}));
			}
			start.countDown();
			Set<UUID> ids = new HashSet<>();
			for (Future<JournalEntryView> result : results) {
				ids.add(result.get().id());
			}
			assertThat(ids).hasSize(1);
		}

		assertThat(balanceOf(tenant)).isEqualTo(500_000);
		assertThat(entriesWithKey(topUp.idempotencyKey())).isOne();
	}

	@Test
	void everyStoredEntryBalancesAndTheBooksSumToZero() {
		ledger.post(topUp(500_000));
		ledger.post(purchase(200_000, 13_953, 3_720, 182_327));

		assertThat(jdbc.queryForList("""
				SELECT journal_entry_id FROM postings
				GROUP BY journal_entry_id
				HAVING sum(CASE direction WHEN 'DEBIT' THEN amount_kobo ELSE 0 END)
					<> sum(CASE direction WHEN 'CREDIT' THEN amount_kobo ELSE 0 END)
					OR count(*) < 2
				""", UUID.class)).isEmpty();
		// DESIGN 6.3: debit-normal balances equal credit-normal balances across the whole ledger.
		assertThat(jdbc.queryForObject("""
				SELECT sum(CASE WHEN type = 'GATEWAY_CLEARING' THEN balance_kobo ELSE -balance_kobo END)
				FROM ledger_accounts
				""", Long.class)).isZero();
	}

	@Test
	void eachBalanceEqualsItsPostings() {
		ledger.post(topUp(500_000));
		ledger.post(purchase(200_000, 13_953, 3_720, 182_327));

		// The stored balance is a running total kept for speed. The postings are the record it must agree with.
		assertThat(jdbc.queryForList("""
				SELECT a.id FROM ledger_accounts a
				LEFT JOIN postings p ON p.account_id = a.id
				GROUP BY a.id, a.type, a.balance_kobo
				HAVING a.balance_kobo <> coalesce(sum(CASE
					WHEN (p.direction = 'DEBIT') = (a.type = 'GATEWAY_CLEARING') THEN p.amount_kobo
					ELSE -p.amount_kobo END), 0)
				""", UUID.class)).isEmpty();
	}

	@Test
	void anAccountThatDoesNotExistIsAnError() {
		JournalEntryCommand toNobody = new JournalEntryCommand(EntryType.ADJUSTMENT, key(), null, null, null,
				List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(1)),
						PostingLine.credit(AccountRef.tenantWallet(UUID.randomUUID()), Money.ofKobo(1))));

		assertThatThrownBy(() -> ledger.post(toNobody)).isInstanceOf(IllegalStateException.class);
	}

	private JournalEntryCommand topUp(long kobo) {
		UUID intent = UUID.randomUUID();
		return new JournalEntryCommand(EntryType.WALLET_TOPUP, "topup:" + intent, "PAYMENT_INTENT", intent,
				"Wallet top-up", List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(kobo)),
						PostingLine.credit(AccountRef.tenantWallet(tenant), Money.ofKobo(kobo))));
	}

	private JournalEntryCommand purchase(long gross, long vat, long fee, long net) {
		return new JournalEntryCommand(EntryType.ENERGY_PURCHASE, key(), null, null, "Energy purchase",
				List.of(PostingLine.debit(AccountRef.tenantWallet(tenant), Money.ofKobo(gross)),
						PostingLine.credit(AccountRef.vatPayable(), Money.ofKobo(vat)),
						PostingLine.credit(AccountRef.platformFeeRevenue(), Money.ofKobo(fee)),
						PostingLine.credit(AccountRef.landlordRevenue(landlord), Money.ofKobo(net))));
	}

	private int entriesWithKey(String key) {
		return jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, key);
	}

	private long balanceOf(UUID owner) {
		return jdbc.queryForObject("SELECT balance_kobo FROM ledger_accounts WHERE owner_id = ?", Long.class, owner);
	}

	private long platformBalance(String type) {
		return jdbc.queryForObject("SELECT balance_kobo FROM ledger_accounts WHERE owner_type = 'PLATFORM' AND type = ?",
				Long.class, type);
	}

	private List<Map<String, Object>> postingsOf(UUID entry) {
		return jdbc.queryForList("SELECT direction, amount_kobo FROM postings WHERE journal_entry_id = ?", entry);
	}

	private UUID user(String role) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO users (id, email, phone, password_hash, full_name, role, status, created_at, updated_at, version)
				VALUES (?, ?, ?, 'not-a-real-hash', 'Ledger User', ?, 'ACTIVE', now(), now(), 0)
				""", id, "user-" + id + "@example.com", "+234803" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000),
				role);
		return id;
	}

	private static String key() {
		return "test:" + UUID.randomUUID();
	}

}
