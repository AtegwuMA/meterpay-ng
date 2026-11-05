package com.theodomart.energy.property.serviceImpl;

import org.hibernate.exception.ConstraintViolationException;

/** Which database constraint rejected a write, so it can become the right domain error. */
final class ConstraintNames {

	private ConstraintNames() {
	}

	static String of(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getConstraintName();
			}
		}
		return null;
	}

}
