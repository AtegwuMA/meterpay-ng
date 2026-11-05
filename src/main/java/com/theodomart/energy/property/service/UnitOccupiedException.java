package com.theodomart.energy.property.service;

import com.theodomart.energy.common.error.DomainException;
import com.theodomart.energy.common.error.ErrorCode;

public class UnitOccupiedException extends DomainException {

	public UnitOccupiedException() {
		super(ErrorCode.UNIT_OCCUPIED, "This unit already has an active tenancy.");
	}

}
