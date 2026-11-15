package com.theodomart.energy.payment.service;

import com.theodomart.energy.common.domain.Money;
import java.time.Instant;

/**
 * The one door to a payment provider (DESIGN 4). Callers never see Paystack's request or response shapes, so another
 * provider, or a stub, can stand in behind this.
 */
public interface PaymentGateway {

	/**
	 * Opens a payment the tenant then completes on the provider's page.
	 *
	 * @param reference our own reference, the intent id, so a payment can always be traced back to its intent
	 * @throws PaymentGatewayException if the provider cannot be reached, refuses, or answers with nonsense
	 */
	Initialization initialize(String reference, Money amount, String email);

	/**
	 * Asks the provider what happened to a payment. The caller must still check the amount and currency against the
	 * intent before believing it (DESIGN 8.1): a provider's "success" for a different amount is not a success.
	 *
	 * @throws PaymentGatewayException as for {@link #initialize}
	 */
	Verification verify(String reference);

	record Initialization(String authorizationUrl, String reference) {

	}

	/**
	 * @param amountKobo what the provider says was charged
	 * @param paidAt null unless the outcome is SUCCESS
	 * @param providerMessage the provider's own words, for logs and support only, never shown to tenants
	 */
	record Verification(Outcome outcome, String reference, long amountKobo, String currency, Instant paidAt,
			String providerMessage) {

	}

	enum Outcome {

		/** The money arrived. */
		SUCCESS,

		/** It will not arrive: declined, failed or reversed. */
		FAILED,

		/** Not finished yet, including a tenant who left the payment page. Asking again later may change it. */
		PENDING

	}

}
