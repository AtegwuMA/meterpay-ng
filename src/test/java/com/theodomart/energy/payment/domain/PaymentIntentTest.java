package com.theodomart.energy.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.common.error.IllegalStateTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentIntentTest {

	private static final Instant NOW = Instant.parse("2026-11-14T09:00:00.123456789Z");

	private static final Instant LATER = NOW.plusSeconds(90);

	private static final String URL = "https://checkout.paystack.com/abc123";

	private final UUID tenant = UUID.randomUUID();

	@Test
	void aNewIntentWaitsToBeSentToTheGateway() {
		PaymentIntent intent = PaymentIntent.initiate(tenant, Money.ofKobo(500_000), "key-1", GatewayProvider.PAYSTACK,
				NOW);

		assertThat(intent.getId()).isNotNull();
		assertThat(intent.getTenantId()).isEqualTo(tenant);
		assertThat(intent.getAmount()).isEqualTo(Money.ofKobo(500_000));
		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.INITIATED);
		assertThat(intent.getGateway()).isEqualTo(GatewayProvider.PAYSTACK);
		assertThat(intent.getIdempotencyKey()).isEqualTo(PaymentIntent.idempotencyKeyFor(tenant, "key-1"));
		assertThat(intent.getCreatedAt()).isEqualTo("2026-11-14T09:00:00.123456Z");
		assertThat(intent.getAuthorizationUrl()).isNull();
		assertThat(intent.getPaidAt()).isNull();
	}

	@Test
	void theHappyPathEndsInPaidWithTheTimeItWasPaid() {
		PaymentIntent intent = initiated();

		intent.markPending(URL, "ref-1", NOW);
		intent.markPaid(LATER);

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(intent.getAuthorizationUrl()).isEqualTo(URL);
		assertThat(intent.getGatewayReference()).isEqualTo("ref-1");
		assertThat(intent.getPaidAt()).isEqualTo(LATER.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
		assertThat(intent.getUpdatedAt()).isEqualTo(intent.getPaidAt());
	}

	@Test
	void paidIsReachedAtMostOnce() {
		PaymentIntent intent = pending();
		intent.markPaid(NOW);

		// A second webhook for the same payment must not be able to mark it paid again.
		assertThatThrownBy(() -> intent.markPaid(LATER)).isInstanceOf(IllegalStateTransitionException.class)
			.hasMessage("Cannot move payment from PAID to PAID.");
		assertThat(intent.getPaidAt()).isEqualTo(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
	}

	@Test
	void aGatewayThatRefusesTheIntentFailsItWithAReason() {
		PaymentIntent intent = initiated();

		intent.markFailed("Gateway rejected the request", NOW);

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(intent.getFailureReason()).isEqualTo("Gateway rejected the request");
	}

	@Test
	void aPendingIntentCanFailOrExpire() {
		PaymentIntent intent = pending();

		intent.markFailed("Expired after 60 minutes", LATER);

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.FAILED);
	}

	@Test
	void aVeryLongReasonIsCutToFitItsColumn() {
		PaymentIntent intent = initiated();

		intent.markFailed("x".repeat(400), NOW);

		assertThat(intent.getFailureReason()).hasSize(PaymentIntent.MAX_FAILURE_REASON_LENGTH);
	}

	@Test
	void onlyAPaidIntentCanBeRefunded() {
		PaymentIntent paid = pending();
		paid.markPaid(NOW);

		paid.markRefunded(LATER);

		assertThat(paid.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		assertThatThrownBy(() -> pending().markRefunded(NOW)).isInstanceOf(IllegalStateTransitionException.class);
	}

	@Test
	void anIntentCannotBePaidWithoutHavingBeenSentToTheGateway() {
		assertThatThrownBy(() -> initiated().markPaid(NOW)).isInstanceOf(IllegalStateTransitionException.class)
			.hasMessage("Cannot move payment from INITIATED to PAID.");
	}

	@Test
	void aFailedIntentStaysFailed() {
		PaymentIntent intent = initiated();
		intent.markFailed("Declined", NOW);

		assertThatThrownBy(() -> intent.markPending(URL, "ref-2", LATER))
			.isInstanceOf(IllegalStateTransitionException.class);
		assertThatThrownBy(() -> intent.markPaid(LATER)).isInstanceOf(IllegalStateTransitionException.class);
		assertThat(intent.getAuthorizationUrl()).isNull();
	}

	@Test
	void pendingNeedsTheUrlAndTheReference() {
		assertThatThrownBy(() -> initiated().markPending(" ", "ref", NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> initiated().markPending(URL, null, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> initiated().markFailed("", NOW)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aTopUpOfZeroIsRefused() {
		assertThatThrownBy(() -> PaymentIntent.initiate(tenant, Money.ZERO, "key", GatewayProvider.PAYSTACK, NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("more than zero");
	}

	@Test
	void theStoredKeyIsAStableHashScopedToTheTenant() {
		String key = PaymentIntent.idempotencyKeyFor(tenant, "same-client-key");

		assertThat(key).matches("[0-9a-f]{64}");
		assertThat(PaymentIntent.idempotencyKeyFor(tenant, "same-client-key")).isEqualTo(key);
		assertThat(PaymentIntent.idempotencyKeyFor(UUID.randomUUID(), "same-client-key")).isNotEqualTo(key);
		assertThat(PaymentIntent.idempotencyKeyFor(tenant, "another-key")).isNotEqualTo(key);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = "   ")
	void anIdempotencyKeyIsRequired(String clientKey) {
		assertThatThrownBy(() -> PaymentIntent.idempotencyKeyFor(tenant, clientKey))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Idempotency-Key");
	}

	@Test
	void aClientKeyLongerThan255IsRefused() {
		assertThatThrownBy(() -> PaymentIntent.idempotencyKeyFor(tenant, "k".repeat(256)))
			.hasMessageContaining("longer than 255");
		assertThat(PaymentIntent.idempotencyKeyFor(tenant, "k".repeat(255))).hasSize(64);
	}

	@Test
	void intentsAreEqualByIdOnly() {
		PaymentIntent intent = initiated();

		assertThat(intent).isEqualTo(intent).hasSameHashCodeAs(intent).isNotEqualTo(initiated()).isNotEqualTo("x");
		assertThat(intent.toString()).contains(intent.getId().toString()).contains("INITIATED");
	}

	private PaymentIntent initiated() {
		return PaymentIntent.initiate(tenant, Money.ofKobo(500_000), UUID.randomUUID().toString(),
				GatewayProvider.PAYSTACK, NOW);
	}

	private PaymentIntent pending() {
		PaymentIntent intent = initiated();
		intent.markPending(URL, "ref-" + UUID.randomUUID(), NOW);
		return intent;
	}

}
