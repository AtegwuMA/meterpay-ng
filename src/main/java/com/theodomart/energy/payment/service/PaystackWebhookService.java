package com.theodomart.energy.payment.service;

public interface PaystackWebhookService {

	/**
	 * Handles one webhook from Paystack (DESIGN 8.1). Returning normally tells Paystack to stop sending it, so this
	 * returns normally for a repeat and for an event we have no use for, and throws only when a retry could help.
	 *
	 * @param rawBody the request body exactly as received: the signature is over these bytes, not over parsed JSON
	 * @param signature the {@code x-paystack-signature} header, or null if it was missing
	 * @throws InvalidWebhookSignatureException if the signature does not match the body
	 * @throws PaymentGatewayException if Paystack could not be asked to confirm the payment; Paystack will retry
	 * @throws com.theodomart.energy.common.error.InvalidRequestException if a signed body is not a Paystack event
	 */
	void receive(byte[] rawBody, String signature);

}
