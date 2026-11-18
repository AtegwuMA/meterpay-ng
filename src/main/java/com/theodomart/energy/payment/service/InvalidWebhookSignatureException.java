package com.theodomart.energy.payment.service;

import com.theodomart.energy.common.error.DomainException;
import com.theodomart.energy.common.error.ErrorCode;

/** The webhook was not signed with our secret key, so nothing in it is believed (401). */
public class InvalidWebhookSignatureException extends DomainException {

	public InvalidWebhookSignatureException() {
		super(ErrorCode.UNAUTHENTICATED, "The webhook signature is not valid.");
	}

}
