package com.theodomart.energy.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.payment.domain.GatewayProvider;
import com.theodomart.energy.payment.domain.PaymentIntent;
import com.theodomart.energy.payment.domain.PaymentStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database's own rules for payments, written around any Java. Each test rolls back, and in Postgres a failed
 * statement ends its transaction, so each one asserts a single rejection, last.
 */
@Transactional
class PaymentSchemaIT extends IntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");

	private final JdbcTemplate jdbc;

	private final EntityManager entityManager;

	private UUID tenant;

	@Autowired
	PaymentSchemaIT(JdbcTemplate jdbc, EntityManager entityManager) {
		this.jdbc = jdbc;
		this.entityManager = entityManager;
	}

	@BeforeEach
	void aTenant() {
		tenant = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO users (id, email, phone, password_hash, full_name, role, status, created_at, updated_at, version)
				VALUES (?, ?, ?, 'not-a-real-hash', 'Payer', 'TENANT', 'ACTIVE', now(), now(), 0)
				""", tenant, "user-" + tenant + "@example.com",
				"+234807" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000));
	}

	@Test
	void anIntentSurvivesTheRoundTripThroughItsWholeLife() {
		PaymentIntent intent = PaymentIntent.initiate(tenant, Money.ofKobo(500_000), "key", GatewayProvider.PAYSTACK,
				NOW);
		entityManager.persist(intent);
		intent.markPending("https://checkout.paystack.com/x", "ref-" + intent.getId(), NOW);
		intent.markPaid(NOW.plusSeconds(60));
		entityManager.flush();
		entityManager.clear();

		PaymentIntent loaded = entityManager.find(PaymentIntent.class, intent.getId());

		assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(loaded.getAmount()).isEqualTo(Money.ofKobo(500_000));
		assertThat(loaded.getPaidAt()).isEqualTo(NOW.plusSeconds(60));
		assertThat(loaded.getIdempotencyKey()).isEqualTo(intent.getIdempotencyKey());
	}

	@Test
	void oneIdempotencyKeyGivesOneIntent() {
		String key = PaymentIntent.idempotencyKeyFor(tenant, "client-key");
		intent("INITIATED", key, null, null, null, null);

		assertRejected("uk_payment_intents_idempotency_key", () -> intent("INITIATED", key, null, null, null, null));
	}

	@Test
	void manyIntentsMayHaveNoGatewayReferenceYetButNoTwoShareOne() {
		intent("INITIATED", hex(), null, null, null, null);
		intent("INITIATED", hex(), null, null, null, null);
		intent("PAYMENT_PENDING", hex(), "ref-1", "https://pay/x", null, null);

		assertRejected("uk_payment_intents_gateway_reference",
				() -> intent("PAYMENT_PENDING", hex(), "ref-1", "https://pay/y", null, null));
	}

	@Test
	void anAmountMustBePositive() {
		assertRejected("ck_payment_intents_amount", () -> jdbc.update("""
				INSERT INTO payment_intents (id, tenant_id, amount_kobo, status, idempotency_key, gateway, created_at,
					updated_at, version)
				VALUES (?, ?, 0, 'INITIATED', ?, 'PAYSTACK', now(), now(), 0)
				""", UUID.randomUUID(), tenant, hex()));
	}

	@Test
	void anUnknownStatusIsRefused() {
		assertRejected("ck_payment_intents_status", () -> intent("SETTLED", hex(), null, null, null, null));
	}

	@Test
	void theStoredKeyMustBeAHash() {
		assertRejected("ck_payment_intents_key_hex", () -> intent("INITIATED", "raw-client-key", null, null, null, null));
	}

	@Test
	void aPaidIntentNeedsThePaidTime() {
		assertRejected("ck_payment_intents_paid_at", () -> intent("PAID", hex(), "ref", "https://pay/x", null, null));
	}

	@Test
	void onlyAPaidOrRefundedIntentHasAPaidTime() {
		assertRejected("ck_payment_intents_paid_at",
				() -> intent("PAYMENT_PENDING", hex(), "ref", "https://pay/x", null, NOW));
	}

	@Test
	void aFailedIntentNeedsAReasonAndOnlyItHasOne() {
		assertRejected("ck_payment_intents_failure", () -> intent("FAILED", hex(), null, null, null, null));
	}

	@Test
	void aReasonOnAnIntentThatDidNotFailIsRefused() {
		assertRejected("ck_payment_intents_failure", () -> intent("INITIATED", hex(), null, null, "Oops", null));
	}

	@Test
	void aPendingIntentNeedsThePaymentPage() {
		assertRejected("ck_payment_intents_authorization", () -> intent("PAYMENT_PENDING", hex(), "ref", null, null, null));
	}

	@Test
	void paymentEventsCannotBeChanged() {
		UUID event = event();

		assertRejected("append_only: UPDATE on payment_events",
				() -> jdbc.update("UPDATE payment_events SET event_type = 'edited' WHERE id = ?", event));
	}

	@Test
	void paymentEventsCannotBeDeleted() {
		UUID event = event();

		assertRejected("append_only: DELETE on payment_events",
				() -> jdbc.update("DELETE FROM payment_events WHERE id = ?", event));
	}

	@Test
	void anEventMayHaveNoIntentWhenItCouldNotBeMatched() {
		jdbc.update("""
				INSERT INTO payment_events (id, intent_id, event_type, payload, correlation_id, created_at)
				VALUES (?, NULL, 'WEBHOOK_UNMATCHED', '{}'::jsonb, 'corr-1', now())
				""", UUID.randomUUID());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events WHERE event_type = 'WEBHOOK_UNMATCHED'",
				Integer.class)).isPositive();
	}

	@Test
	void aGenuineWebhookIsRecordedOnce() {
		String event = "evt-" + UUID.randomUUID();
		receipt(event, true, null);

		assertRejected("uk_webhook_receipts_event", () -> receipt(event, true, null));
	}

	@Test
	void forgedWebhooksCannotClaimARealEventIdAheadOfTheGateway() {
		String event = "evt-" + UUID.randomUUID();
		receipt(event, false, null);
		receipt(event, false, null);

		// The genuine one still gets in, because only correctly signed receipts take part in the dedupe.
		receipt(event, true, null);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_receipts WHERE event_id = ?", Integer.class, event))
			.isEqualTo(3);
	}

	@Test
	void aBadlySignedWebhookCanNeverBeMarkedProcessed() {
		assertRejected("ck_webhook_receipts_processed", () -> receipt("evt-" + UUID.randomUUID(), false, NOW));
	}

	private void intent(String status, String key, String reference, String url, String reason, Instant paidAt) {
		jdbc.update("""
				INSERT INTO payment_intents (id, tenant_id, amount_kobo, status, idempotency_key, gateway, gateway_reference,
					authorization_url, failure_reason, paid_at, created_at, updated_at, version)
				VALUES (?, ?, 500000, ?, ?, 'PAYSTACK', ?, ?, ?, ?, now(), now(), 0)
				""", UUID.randomUUID(), tenant, status, key, reference, url, reason,
				paidAt == null ? null : java.sql.Timestamp.from(paidAt));
	}

	private UUID event() {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO payment_events (id, intent_id, event_type, payload, correlation_id, created_at)
				VALUES (?, NULL, 'INITIALIZE_REQUEST', '{"amount": 500000}'::jsonb, 'corr-1', now())
				""", id);
		return id;
	}

	private void receipt(String eventId, boolean signatureValid, Instant processedAt) {
		jdbc.update("""
				INSERT INTO webhook_receipts (id, gateway, event_id, signature_valid, payload, received_at, processed_at)
				VALUES (?, 'PAYSTACK', ?, ?, '{}'::jsonb, now(), ?)
				""", UUID.randomUUID(), eventId, signatureValid,
				processedAt == null ? null : java.sql.Timestamp.from(processedAt));
	}

	private static String hex() {
		return PaymentIntent.idempotencyKeyFor(UUID.randomUUID(), "k");
	}

	private static void assertRejected(String constraint, Runnable statement) {
		assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(constraint);
	}

}
