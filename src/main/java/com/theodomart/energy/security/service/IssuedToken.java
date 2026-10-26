package com.theodomart.energy.security.service;

import java.time.Instant;

public record IssuedToken(String value, Instant issuedAt, Instant expiresAt) {

	@Override
	public String toString() {
		// The token is a bearer credential: anyone holding it is the user until it expires. Keep it out of logs.
		return "IssuedToken[issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
	}

}
