package com.theodomart.energy.payment.service;

import com.theodomart.energy.common.domain.Money;
import java.util.UUID;

public interface TopUpService {

	/**
	 * Starts a wallet top-up (DESIGN 8.1). The intent is committed before Paystack is called, and the same key always
	 * means the same intent: a retry gets the first answer, and Paystack is called once.
	 *
	 * @param clientKey the client's Idempotency-Key header
	 * @throws com.theodomart.energy.common.error.IdempotencyConflictException if the key was used for another amount
	 * @throws PaymentGatewayException if Paystack could not take the payment; the intent is kept either way
	 */
	StartedTopUp start(UUID tenantId, String clientKey, Money amount);

	/**
	 * The tenant's own top-up as it stands. While it is still waiting on payment, asks Paystack first and settles it
	 * by the webhook's rules, so a tenant back from the payment page sees PAID even if the webhook is late (DESIGN
	 * 8.1). If Paystack cannot be asked, returns the intent as stored rather than failing.
	 *
	 * @throws com.theodomart.energy.common.error.ResourceNotFoundException if there is no such top-up, or it is
	 * another tenant's
	 */
	TopUpView status(UUID tenantId, UUID intentId);

	/** {@code created} is false when this is a repeat of an earlier request with the same key. */
	record StartedTopUp(TopUpView topUp, boolean created) {

	}

}
