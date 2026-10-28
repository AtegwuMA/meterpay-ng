package com.theodomart.energy.identity.service;

import com.theodomart.energy.common.error.DomainException;
import com.theodomart.energy.common.error.ErrorCode;

public class InvalidRefreshTokenException extends DomainException {

	public InvalidRefreshTokenException() {
		// Same answer for unknown, expired, revoked and reused tokens: the caller learns nothing about which.
		super(ErrorCode.UNAUTHENTICATED, "Refresh token is invalid or expired.");
	}

}
