package com.theodomart.energy.common.error;

/**
 * 404. Also used when the resource exists but belongs to someone else (DESIGN 10), so the response never
 * confirms that an id is real.
 */
public class ResourceNotFoundException extends DomainException {

	public ResourceNotFoundException(String detail) {
		super(ErrorCode.NOT_FOUND, detail);
	}

}
