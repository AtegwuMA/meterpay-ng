package com.theodomart.energy.common.error;

/**
 * The same idempotency key arrived with a different request. Replaying the first result would hide that the caller
 * meant something else, and acting on the second would break the promise the key makes, so neither happens.
 */
public class IdempotencyConflictException extends DomainException {

	public IdempotencyConflictException() {
		super(ErrorCode.IDEMPOTENCY_KEY_REUSED, "This idempotency key was already used for a different request.");
	}

}
