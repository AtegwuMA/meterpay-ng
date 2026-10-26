package com.theodomart.energy.common.error;

/** A request that passed bean validation but breaks a rule only the service can check. Maps to 400. */
public class InvalidRequestException extends DomainException {

	public InvalidRequestException(String detail) {
		super(ErrorCode.VALIDATION_FAILED, detail);
	}

}
