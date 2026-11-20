package com.theodomart.energy.payment.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.payment.domain.PaymentEventType;
import com.theodomart.energy.payment.service.PaymentGateway.Initialization;
import com.theodomart.energy.payment.service.PaymentGateway.Outcome;
import com.theodomart.energy.payment.service.PaymentGateway.Verification;
import com.theodomart.energy.payment.service.PaymentGatewayException;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RecordingPaymentGatewayTest {

	private final PaystackGateway paystack = mock();

	private final PaymentEventRecorder recorder = mock();

	private final RecordingPaymentGateway gateway = new RecordingPaymentGateway(paystack, recorder,
			JsonMapper.builder().build());

	private final UUID intent = UUID.randomUUID();

	@Test
	void aSuccessfulInitializeIsRecordedAgainstItsIntentWithoutTheEmail() {
		when(paystack.initialize(intent.toString(), Money.ofKobo(500_000), "tolu@example.com"))
			.thenReturn(new Initialization("https://checkout.paystack.com/x", intent.toString()));

		Initialization result = gateway.initialize(intent.toString(), Money.ofKobo(500_000), "tolu@example.com");

		assertThat(result.authorizationUrl()).isEqualTo("https://checkout.paystack.com/x");
		ObjectNode payload = recorded(intent, PaymentEventType.INITIALIZE_SUCCEEDED);
		assertThat(payload.get("amountKobo").asLong()).isEqualTo(500_000);
		assertThat(payload.get("authorizationUrl").asString()).isEqualTo("https://checkout.paystack.com/x");
		assertThat(payload.has("durationMs")).isTrue();
		assertThat(payload.toString()).doesNotContain("tolu@example.com");
	}

	@Test
	void aFailedCallIsRecordedAndStillThrown() {
		PaymentGatewayException failure = PaymentGatewayException.unreachable(new IOException("reset"));
		when(paystack.initialize(any(), any(), any())).thenThrow(failure);

		assertThatThrownBy(() -> gateway.initialize(intent.toString(), Money.ofKobo(100), "a@example.com"))
			.isSameAs(failure);

		ObjectNode payload = recorded(intent, PaymentEventType.INITIALIZE_FAILED);
		assertThat(payload.get("retryable").asBoolean()).isTrue();
		assertThat(payload.get("providerStatus").asInt()).isZero();
		assertThat(payload.get("error").asString()).isEqualTo("IOException");
	}

	@Test
	void aRefusalIsRecordedWithTheProvidersStatus() {
		when(paystack.verify(any())).thenThrow(PaymentGatewayException.rejected(401));

		assertThatThrownBy(() -> gateway.verify(intent.toString())).isInstanceOf(PaymentGatewayException.class);

		ObjectNode payload = recorded(intent, PaymentEventType.VERIFY_FAILED);
		assertThat(payload.get("providerStatus").asInt()).isEqualTo(401);
		assertThat(payload.get("error").asString()).isEqualTo("rejected");
	}

	@Test
	void aVerifyAnswerIsRecordedWhateverItsOutcome() {
		when(paystack.verify(intent.toString())).thenReturn(new Verification(Outcome.SUCCESS, intent.toString(),
				500_000, "NGN", Instant.parse("2026-11-20T10:00:00Z"), "Approved"));

		gateway.verify(intent.toString());

		ObjectNode payload = recorded(intent, PaymentEventType.VERIFY_SUCCEEDED);
		assertThat(payload.get("outcome").asString()).isEqualTo("SUCCESS");
		assertThat(payload.get("paidAt").asString()).isEqualTo("2026-11-20T10:00:00Z");
		assertThat(payload.get("providerMessage").asString()).isEqualTo("Approved");
	}

	@Test
	void anUnfinishedPaymentHasNoPaidTimeInTheLog() {
		when(paystack.verify(intent.toString()))
			.thenReturn(new Verification(Outcome.PENDING, intent.toString(), 500_000, "NGN", null, "Abandoned"));

		gateway.verify(intent.toString());

		assertThat(recorded(intent, PaymentEventType.VERIFY_SUCCEEDED).get("paidAt").isNull()).isTrue();
	}

	@Test
	void aReferenceThatIsNotOneOfOursIsLoggedWithoutAnIntent() {
		when(paystack.verify("T123")).thenReturn(new Verification(Outcome.FAILED, "T123", 1, "NGN", null, "No"));

		gateway.verify("T123");

		verify(recorder).record(isNull(), eq(PaymentEventType.VERIFY_SUCCEEDED), any());
	}

	@Test
	void onlyTheCanonicalSpellingOfAnIntentIdCounts() {
		assertThat(RecordingPaymentGateway.intentIdOf(intent.toString())).contains(intent);
		assertThat(RecordingPaymentGateway.intentIdOf("1-1-1-1-1")).isEmpty();
		assertThat(RecordingPaymentGateway.intentIdOf("not-a-uuid")).isEmpty();
		assertThat(RecordingPaymentGateway.intentIdOf(null)).isEmpty();
	}

	private ObjectNode recorded(UUID intentId, PaymentEventType type) {
		ArgumentCaptor<ObjectNode> payload = ArgumentCaptor.forClass(ObjectNode.class);
		verify(recorder).record(eq(intentId), eq(type), payload.capture());
		return payload.getValue();
	}

}
