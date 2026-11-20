package com.theodomart.energy.payment.domain;

/** What one row of the payment log records (DESIGN 6.2, M4-07). Stored by name, so names must not change. */
public enum PaymentEventType {

	/** We asked the gateway to open a payment and it gave us a payment page. */
	INITIALIZE_SUCCEEDED,

	/** We asked the gateway to open a payment and got an error, a refusal or no answer. */
	INITIALIZE_FAILED,

	/** We asked the gateway what happened to a payment and got an answer, whatever the outcome. */
	VERIFY_SUCCEEDED,

	/** We asked the gateway what happened to a payment and got an error, a refusal or no answer. */
	VERIFY_FAILED,

	/** The gateway sent us a correctly signed webhook. */
	WEBHOOK_RECEIVED

}
