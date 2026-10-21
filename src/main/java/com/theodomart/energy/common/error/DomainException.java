package com.theodomart.energy.common.error;

/**
 * Base for every business rule violation. The message becomes the problem {@code detail} and is
 * shown to clients, so it must never contain internal data (SQL, class names, secrets).
 */
public abstract class DomainException extends RuntimeException {

	private final ErrorCode errorCode;

	protected DomainException(ErrorCode errorCode, String detail) {
		super(detail);
		this.errorCode = errorCode;
	}

	public ErrorCode errorCode() {
		return errorCode;
	}

}
