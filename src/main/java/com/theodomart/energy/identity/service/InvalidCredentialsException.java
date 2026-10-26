package com.theodomart.energy.identity.service;

import com.theodomart.energy.common.error.DomainException;
import com.theodomart.energy.common.error.ErrorCode;

public class InvalidCredentialsException extends DomainException {

	public InvalidCredentialsException() {
		// One message for every failure reason, so the response does not reveal which emails are registered.
		super(ErrorCode.UNAUTHENTICATED, "Invalid email or password.");
	}

}
