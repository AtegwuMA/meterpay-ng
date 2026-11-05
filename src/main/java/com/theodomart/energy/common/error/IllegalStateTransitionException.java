package com.theodomart.energy.common.error;

/** 409 when something asks a state machine for a move it does not allow (DESIGN 7). */
public class IllegalStateTransitionException extends DomainException {

	public IllegalStateTransitionException(String subject, Enum<?> from, Enum<?> to) {
		super(ErrorCode.ILLEGAL_STATE_TRANSITION, "Cannot move " + subject + " from " + from + " to " + to + ".");
	}

}
