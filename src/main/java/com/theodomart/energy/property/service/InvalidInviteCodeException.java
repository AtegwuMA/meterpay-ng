package com.theodomart.energy.property.service;

import com.theodomart.energy.common.error.DomainException;
import com.theodomart.energy.common.error.ErrorCode;

public class InvalidInviteCodeException extends DomainException {

	public InvalidInviteCodeException() {
		// One message for unknown, used, cancelled and expired, so guessing reveals nothing about real codes.
		super(ErrorCode.INVALID_INVITE_CODE, "This invite code is invalid or has expired. Ask your landlord for a new one.");
	}

}
