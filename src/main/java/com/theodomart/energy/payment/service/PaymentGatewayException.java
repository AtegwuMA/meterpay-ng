package com.theodomart.energy.payment.service;

import com.theodomart.energy.common.error.DomainException;
import com.theodomart.energy.common.error.ErrorCode;

/**
 * The payment provider could not be reached, refused the request, or sent something we could not read (502). The
 * message shown to the client is always the same: the provider's own words and our credentials stay in our logs.
 */
public class PaymentGatewayException extends DomainException {

	private final boolean retryable;

	private final int providerStatus;

	private PaymentGatewayException(boolean retryable, int providerStatus, Throwable cause) {
		super(ErrorCode.PAYMENT_GATEWAY_ERROR, "The payment provider is not available right now. Try again shortly.");
		this.retryable = retryable;
		this.providerStatus = providerStatus;
		if (cause != null) {
			initCause(cause);
		}
	}

	/** A timeout or a connection that failed: the request may or may not have arrived, so asking again is right. */
	public static PaymentGatewayException unreachable(Throwable cause) {
		return new PaymentGatewayException(true, 0, cause);
	}

	/** The provider answered with an error status. Its own 5xx and 429 are worth retrying; a 4xx means we are wrong. */
	public static PaymentGatewayException rejected(int status) {
		return new PaymentGatewayException(status >= 500 || status == 429, status, null);
	}

	/** The provider said yes in a shape we cannot use. Retrying will not change it. */
	public static PaymentGatewayException unreadable() {
		return new PaymentGatewayException(false, 0, null);
	}

	public boolean isRetryable() {
		return retryable;
	}

	/** The HTTP status the provider returned, or 0 when there was no answer or it could not be read. */
	public int providerStatus() {
		return providerStatus;
	}

}
