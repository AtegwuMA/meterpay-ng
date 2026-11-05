package com.theodomart.energy.common.persistence;

import org.hibernate.exception.ConstraintViolationException;

/** Which database constraint rejected a write, so a service can turn it into the right domain error. */
public final class ConstraintViolations {

	private ConstraintViolations() {
	}

	/** @return the constraint or index name, or null if the failure was not a constraint violation */
	public static String constraintName(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getConstraintName();
			}
		}
		return null;
	}

}
