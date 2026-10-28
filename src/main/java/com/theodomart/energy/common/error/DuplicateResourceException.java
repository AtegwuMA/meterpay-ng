package com.theodomart.energy.common.error;

/** 409 for a create that collides with a uniqueness rule, raised after the database constraint fires. */
public class DuplicateResourceException extends DomainException {

	public DuplicateResourceException(String detail) {
		super(ErrorCode.DUPLICATE_RESOURCE, detail);
	}

}
