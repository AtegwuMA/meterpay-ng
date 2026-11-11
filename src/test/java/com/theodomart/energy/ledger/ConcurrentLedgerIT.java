package com.theodomart.energy.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.EntryType;
import com.theodomart.energy.ledger.service.InsufficientWalletBalanceException;
import com.theodomart.energy.ledger.service.JournalEntryCommand;
import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.PostingLine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Many debits against one wallet at once. "No exception thrown" would prove nothing here, so every attempt is counted
 * by its outcome, and the money is checked to the kobo against what the counts say must have happened.
 */
class ConcurrentLedgerIT extends IntegrationTest {

	private static final long PRICE = 10_000;

	/** Enough for exactly this many debits, so twenty threads must be refused at least ten times between them. */
	private static final int AFFORDABLE = 10;

	private static final int THREADS = 20;

	private enum Outcome {

		SUCCEEDED, INSUFFICIENT_FUNDS, GAVE_UP_AFTER_RETRIES

	}

	private final LedgerService ledger;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private UUID tenant;

	private UUID landlord;

	@Autowired
	ConcurrentLedgerIT(LedgerService ledger, JdbcTemplate jdbc, TransactionTemplate transaction) {
		this.ledger = ledger;
		this.jdbc = jdbc;
		this.transaction = transaction;
	}

	@BeforeEach
	void fundedWallet() {
		tenant = user("TENANT");
		landlord = user("LANDLORD");
		transaction.executeWithoutResult(status -> {
			ledger.openTenantWallet(tenant);
			ledger.openLandlordRevenueAccount(landlord);
		});
		ledger.post(new JournalEntryCommand(EntryType.WALLET_TOPUP, key(), null, null, null,
				List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(PRICE * AFFORDABLE)),
						PostingLine.credit(AccountRef.tenantWallet(tenant), Money.ofKobo(PRICE * AFFORDABLE)))));
	}

	@Test
	void parallelDebitsNeverOverdrawWallet() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Outcome>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
			for (int i = 0; i < THREADS; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return debit();
				}));
			}
			start.countDown();
		}
		Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
		for (Outcome outcome : Outcome.values()) {
			counts.put(outcome, 0);
		}
		for (Future<Outcome> future : futures) {
			// Anything other than the three expected outcomes fails the test here, with its own exception.
			counts.merge(future.get(), 1, Integer::sum);
		}
		int succeeded = counts.get(Outcome.SUCCEEDED);
		int insufficient = counts.get(Outcome.INSUFFICIENT_FUNDS);
		int gaveUp = counts.get(Outcome.GAVE_UP_AFTER_RETRIES);
		long balance = walletBalance();
		System.out.printf("20 parallel debits: %d succeeded, %d insufficient funds, %d gave up after retries, "
				+ "balance %d kobo%n", succeeded, insufficient, gaveUp, balance);

		assertThat(succeeded + insufficient + gaveUp).as("every attempt has exactly one outcome").isEqualTo(THREADS);
		assertThat(balance).as("never below zero").isGreaterThanOrEqualTo(0);
		assertThat(balance).as("exactly the debits that succeeded").isEqualTo(PRICE * (AFFORDABLE - succeeded));
		assertThat(succeeded).as("no more than the wallet could pay for").isBetween(1, AFFORDABLE);
		if (insufficient > 0) {
			// The balance only falls in this test, so a refusal for lack of funds must mean the funds really ran out.
			assertThat(balance).as("refused for lack of funds while money remained").isLessThan(PRICE);
		}
		assertThat(landlordBalance()).isEqualTo(PRICE * succeeded);
		assertThat(debitEntries()).isEqualTo(succeeded);
		assertTheBooksStillBalance();
	}

	@Test
	void anEntryThatLosesARaceIsRetriedAndSucceeds() throws Exception {
		long debited = raceAgainstAnOpenTransaction(() -> {
			ledger.post(debitCommand());
			return true;
		});

		assertThat(debited).isEqualTo(2 * PRICE);
		assertTheBooksStillBalance();
	}

	@Test
	void insideACallersTransactionTheConflictIsLeftToTheCaller() throws Exception {
		long debited = raceAgainstAnOpenTransaction(() -> {
			assertThatThrownBy(() -> transaction.executeWithoutResult(status -> ledger.post(debitCommand())))
				.isInstanceOf(OptimisticLockingFailureException.class);
			return true;
		});

		assertThat(debited).as("only the first debit went through").isEqualTo(PRICE);
		assertTheBooksStillBalance();
	}

	/**
	 * One transaction debits the wallet and stays open until Postgres shows the second debit waiting on the wallet's
	 * row, so the second is certain to have read the old version. Then it commits, and the second loses the race.
	 * Returns how much left the wallet in all.
	 */
	private long raceAgainstAnOpenTransaction(java.util.concurrent.Callable<Boolean> second) throws Exception {
		CountDownLatch firstPosted = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> first = pool.submit(() -> transaction.executeWithoutResult(status -> {
				ledger.post(debitCommand());
				firstPosted.countDown();
				waitForALockWaiter();
			}));
			Future<Boolean> other = pool.submit(() -> {
				firstPosted.await();
				return second.call();
			});
			first.get(30, TimeUnit.SECONDS);
			assertThat(other.get(30, TimeUnit.SECONDS)).isTrue();
		}
		return PRICE * AFFORDABLE - walletBalance();
	}

	private void waitForALockWaiter() {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			Integer waiting = jdbc.queryForObject(
					"SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
					Integer.class);
			if (waiting != null && waiting > 0) {
				return;
			}
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		throw new AssertionError("The second debit never waited on the wallet's row");
	}

	private Outcome debit() {
		try {
			ledger.post(debitCommand());
			return Outcome.SUCCEEDED;
		}
		catch (InsufficientWalletBalanceException ex) {
			return Outcome.INSUFFICIENT_FUNDS;
		}
		catch (OptimisticLockingFailureException ex) {
			return Outcome.GAVE_UP_AFTER_RETRIES;
		}
	}

	private JournalEntryCommand debitCommand() {
		return new JournalEntryCommand(EntryType.ENERGY_PURCHASE, key(), null, null, null,
				List.of(PostingLine.debit(AccountRef.tenantWallet(tenant), Money.ofKobo(PRICE)),
						PostingLine.credit(AccountRef.landlordRevenue(landlord), Money.ofKobo(PRICE))));
	}

	private void assertTheBooksStillBalance() {
		assertThat(jdbc.queryForObject("""
				SELECT sum(CASE WHEN type = 'GATEWAY_CLEARING' THEN balance_kobo ELSE -balance_kobo END)
				FROM ledger_accounts
				""", Long.class)).isZero();
		assertThat(jdbc.queryForList("""
				SELECT a.id FROM ledger_accounts a
				LEFT JOIN postings p ON p.account_id = a.id
				GROUP BY a.id, a.type, a.balance_kobo
				HAVING a.balance_kobo <> coalesce(sum(CASE
					WHEN (p.direction = 'DEBIT') = (a.type = 'GATEWAY_CLEARING') THEN p.amount_kobo
					ELSE -p.amount_kobo END), 0)
				""", UUID.class)).isEmpty();
	}

	private long walletBalance() {
		return jdbc.queryForObject("SELECT balance_kobo FROM ledger_accounts WHERE owner_id = ?", Long.class, tenant);
	}

	private long landlordBalance() {
		return jdbc.queryForObject("SELECT balance_kobo FROM ledger_accounts WHERE owner_id = ?", Long.class, landlord);
	}

	private int debitEntries() {
		return jdbc.queryForObject("""
				SELECT count(*) FROM postings p JOIN ledger_accounts a ON a.id = p.account_id
				WHERE a.owner_id = ? AND p.direction = 'DEBIT'
				""", Integer.class, tenant);
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
