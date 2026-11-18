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

	/** {@code created} is false when this is a repeat of an earlier request with the same key. */
	record StartedTopUp(TopUpView topUp, boolean created) {

	}

}
