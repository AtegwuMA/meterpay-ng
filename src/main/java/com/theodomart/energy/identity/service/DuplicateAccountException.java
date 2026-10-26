package com.theodomart.energy.identity.service;

import com.theodomart.energy.common.error.DomainException;
import com.theodomart.energy.common.error.ErrorCode;

public class DuplicateAccountException extends DomainException {

	public DuplicateAccountException(String field) {
		super(ErrorCode.DUPLICATE_ACCOUNT, "An account with this " + field + " already exists.");
	}

}
