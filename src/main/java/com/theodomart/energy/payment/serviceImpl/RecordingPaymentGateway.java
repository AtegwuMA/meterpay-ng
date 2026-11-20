package com.theodomart.energy.payment.serviceImpl;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.payment.domain.PaymentEventType;
import com.theodomart.energy.payment.service.PaymentGateway;
import com.theodomart.energy.payment.service.PaymentGatewayException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The gateway every caller gets: Paystack, with each call written to the payment log (M4-07). A decorator, so the
 * adapter stays about HTTP and no caller can forget to log.
 * <p>
 * The payload holds what we sent and what we understood of the answer, not Paystack's raw body: that carries the
 * tenant's email and card details we have no reason to keep. Calls happen outside any transaction (see
 * TopUpServiceImpl), so the log row's own transaction never waits on a lock its caller holds.
 */
@Primary
@Component
public class RecordingPaymentGateway implements PaymentGateway {

	private final PaystackGateway paystack;

	private final PaymentEventRecorder recorder;

	private final ObjectMapper json;

	public RecordingPaymentGateway(PaystackGateway paystack, PaymentEventRecorder recorder, ObjectMapper json) {
		this.paystack = paystack;
		this.recorder = recorder;
		this.json = json;
	}

	@Override
	public Initialization initialize(String reference, Money amount, String email) {
		ObjectNode request = json.createObjectNode().put("reference", reference).put("amountKobo", amount.kobo());
		return call(reference, PaymentEventType.INITIALIZE_FAILED, request,
				() -> paystack.initialize(reference, amount, email));
	}

	@Override
	public Verification verify(String reference) {
		ObjectNode request = json.createObjectNode().put("reference", reference);
		return call(reference, PaymentEventType.VERIFY_FAILED, request, () -> paystack.verify(reference));
	}

	private <T> T call(String reference, PaymentEventType failure, ObjectNode payload, Supplier<T> work) {
		UUID intentId = intentIdOf(reference).orElse(null);
		long started = System.nanoTime();
		T result;
		try {
			result = work.get();
		}
		catch (PaymentGatewayException ex) {
			payload.put("durationMs", elapsedMillis(started))
				.put("retryable", ex.isRetryable())
				.put("providerStatus", ex.providerStatus())
				.put("error", ex.getCause() == null ? "rejected" : ex.getCause().getClass().getSimpleName());
			recorder.record(intentId, failure, payload);
			throw ex;
		}
		payload.put("durationMs", elapsedMillis(started));
		if (result instanceof Initialization initialization) {
			payload.put("gatewayReference", initialization.reference())
				.put("authorizationUrl", initialization.authorizationUrl());
			recorder.record(intentId, PaymentEventType.INITIALIZE_SUCCEEDED, payload);
		}
		else if (result instanceof Verification verification) {
			payload.put("outcome", verification.outcome().name())
				.put("amountKobo", verification.amountKobo())
				.put("currency", verification.currency())
				.put("paidAt", verification.paidAt() == null ? null : verification.paidAt().toString())
				.put("providerMessage", verification.providerMessage());
			recorder.record(intentId, PaymentEventType.VERIFY_SUCCEEDED, payload);
		}
		return result;
	}

	/** Our references are intent ids. Anything else is logged without one rather than guessed at. */
	static Optional<UUID> intentIdOf(String reference) {
		try {
			UUID id = UUID.fromString(reference);
			// UUID.fromString accepts short forms like "1-1-1-1-1"; only the canonical spelling is ours.
			return id.toString().equals(reference) ? Optional.of(id) : Optional.empty();
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			return Optional.empty();
		}
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}

}
