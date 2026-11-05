package com.theodomart.energy.property.service;

import java.time.Instant;
import java.util.UUID;

/** Holds the plain code. This is the only moment it exists on the server: only its hash is stored. */
public record InviteView(String code, UUID unitId, Instant expiresAt) {

	@Override
	public String toString() {
		return "InviteView[unitId=" + unitId + ", expiresAt=" + expiresAt + "]";
	}

}
