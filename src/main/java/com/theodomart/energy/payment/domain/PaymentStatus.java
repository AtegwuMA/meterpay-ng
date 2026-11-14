package com.theodomart.energy.payment.domain;

/** The states of DESIGN 7.1, and the only place that says which moves between them are allowed. */
public enum PaymentStatus {

	INITIATED, PAYMENT_PENDING, PAID, FAILED, REFUNDED;

	public boolean canTransitionTo(PaymentStatus target) {
		// Written as an exhaustive switch so a new state will not compile until its moves are decided.
		return switch (this) {
			case INITIATED -> target == PAYMENT_PENDING || target == FAILED;
			case PAYMENT_PENDING -> target == PAID || target == FAILED;
			// PAID is reached at most once, and the only way on is a refund: money that arrived never "un-arrives".
			case PAID -> target == REFUNDED;
			case FAILED, REFUNDED -> false;
		};
	}

	/** Nothing more will happen to the intent. */
	public boolean isFinal() {
		return this == FAILED || this == REFUNDED;
	}

}
