package com.theodomart.energy.payment.serviceImpl;

import com.theodomart.energy.common.web.CorrelationIdFilter;
import com.theodomart.energy.payment.config.ReconciliationProperties;
import com.theodomart.energy.payment.domain.PaymentIntent;
import com.theodomart.energy.payment.domain.PaymentStatus;
import com.theodomart.energy.payment.repository.PaymentIntentRepository;
import com.theodomart.energy.payment.service.PaymentGateway;
import com.theodomart.energy.payment.service.PaymentGateway.Outcome;
import com.theodomart.energy.payment.service.PaymentGateway.Verification;
import com.theodomart.energy.payment.service.PaymentGatewayException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The fallback when a webhook never comes (DESIGN 8.1): every few minutes, asks Paystack about top-ups that have
 * waited too long, and settles them by the same rules as the webhook ({@link TopUpSettlement}).
 * <p>
 * One instance at a time, through a Postgres advisory lock rather than a library: two instances would only repeat
 * each other's verify calls (settling is safe under the intent lock either way), but they would double the load on
 * Paystack for nothing.
 */
@Component
public class TopUpReconciliationJob {

	/** Any fixed number all instances agree on. Spells "MPRECONC" in ASCII, to be recognisable in pg_locks. */
	static final long LOCK_KEY = 0x4D50_5245_434F_4E43L;

	private static final Logger log = LoggerFactory.getLogger(TopUpReconciliationJob.class);

	private static final Set<PaymentStatus> UNSETTLED = EnumSet.of(PaymentStatus.INITIATED,
			PaymentStatus.PAYMENT_PENDING);

	private final PaymentIntentRepository intents;

	private final PaymentGateway gateway;

	private final TopUpSettlement settlement;

	private final ReconciliationProperties properties;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public TopUpReconciliationJob(PaymentIntentRepository intents, PaymentGateway gateway, TopUpSettlement settlement,
			ReconciliationProperties properties, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
			Clock clock) {
		this.intents = intents;
		this.gateway = gateway;
		this.settlement = settlement;
		this.properties = properties;
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** What one run did, for logs and tests. */
	public record Run(boolean ran, int checked, int paid, int failed, int unchanged, int errors) {

		static Run skipped() {
			return new Run(false, 0, 0, 0, 0, 0);
		}

	}

	@Scheduled(initialDelayString = "${meterpay.payment.reconciliation.interval}",
			fixedDelayString = "${meterpay.payment.reconciliation.interval}")
	public Run run() {
		// No request, so no correlation ID from a filter: one per run ties its log lines together.
		MDC.put(CorrelationIdFilter.MDC_KEY, "reconcile-" + UUID.randomUUID());
		try {
			Run result = whileHoldingLock();
			if (result.ran() && result.checked() > 0) {
				log.info("Top-up reconciliation: checked={} paid={} failed={} unchanged={} errors={}",
						result.checked(), result.paid(), result.failed(), result.unchanged(), result.errors());
			}
			return result;
		}
		finally {
			MDC.remove(CorrelationIdFilter.MDC_KEY);
		}
	}

	/**
	 * A session-level lock, held on one pooled connection for the run and released in finally. A transaction-level
	 * lock would mean one transaction open across every call to Paystack, which is what this module avoids.
	 * If the instance dies, Postgres drops the lock with its connection.
	 */
	private Run whileHoldingLock() {
		return jdbc.execute((ConnectionCallback<Run>) connection -> {
			if (!advisoryLock(connection, "SELECT pg_try_advisory_lock(?)")) {
				log.debug("Top-up reconciliation already running elsewhere");
				return Run.skipped();
			}
			try {
				return reconcile();
			}
			finally {
				advisoryLock(connection, "SELECT pg_advisory_unlock(?)");
			}
		});
	}

	private static boolean advisoryLock(Connection connection, String sql) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, LOCK_KEY);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		}
	}

	Run reconcile() {
		Instant now = clock.instant();
		List<UUID> due = intents.findIdsByStatusCreatedBefore(UNSETTLED, now.minus(properties.staleAfter()),
				Limit.of(properties.batchSize()));
		int paid = 0;
		int failed = 0;
		int unchanged = 0;
		int errors = 0;
		for (UUID id : due) {
			try {
				switch (reconcile(id, now)) {
					case PAID -> paid++;
					case FAILED -> failed++;
					default -> unchanged++;
				}
			}
			catch (RuntimeException ex) {
				// One bad intent must not stop the rest; it is picked up again next run.
				errors++;
				log.warn("Top-up reconciliation failed for one intent: intentId={} error={}", id,
						ex.getClass().getSimpleName());
			}
		}
		return new Run(true, due.size(), paid, failed, unchanged, errors);
	}

	/** Returns the intent's status afterwards. */
	private PaymentStatus reconcile(UUID id, Instant now) {
		// Verify outside any transaction, as everywhere in this module.
		Verification verification;
		try {
			verification = gateway.verify(id.toString());
		}
		catch (PaymentGatewayException ex) {
			if (ex.isRetryable()) {
				log.warn("Top-up left for the next run, gateway unavailable: intentId={}", id);
				return PaymentStatus.PAYMENT_PENDING;
			}
			// Paystack does not know the reference. For an intent that never got a payment page that is the
			// expected answer, and it can fail. A pending intent it should know: a person should look.
			return inTransaction(id, intent -> {
				if (intent.getStatus() == PaymentStatus.INITIATED) {
					settlement.fail(intent, "Never reached the gateway");
				}
				else {
					log.warn("Gateway has no record of a pending top-up: intentId={} providerStatus={}", id,
							ex.providerStatus());
				}
			});
		}
		return inTransaction(id, intent -> {
			settlement.apply(intent, verification);
			if (verification.outcome() == Outcome.SUCCESS) {
				return;
			}
			if (intent.getStatus() == PaymentStatus.INITIATED) {
				// Nobody was ever shown the payment page, so nobody can pay it now.
				settlement.fail(intent, "Payment page was never shown; not paid at the gateway");
			}
			else if (intent.getStatus() == PaymentStatus.PAYMENT_PENDING
					&& !intent.getCreatedAt().plus(properties.expireAfter()).isAfter(now)) {
				// DESIGN 7.1: expired, and only after this final verify said it was still not paid.
				settlement.fail(intent, "Expired unpaid after " + properties.expireAfter().toMinutes() + " minutes");
			}
		});
	}

	private PaymentStatus inTransaction(UUID id, Consumer<PaymentIntent> work) {
		return transactions.execute(status -> {
			PaymentIntent intent = intents.findByIdForUpdate(id).orElseThrow();
			// Settled by a webhook while we were asking Paystack: nothing left to do.
			if (UNSETTLED.contains(intent.getStatus())) {
				work.accept(intent);
			}
			return intent.getStatus();
		});
	}

}
