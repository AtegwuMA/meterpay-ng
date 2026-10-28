package com.theodomart.energy.identity.service;

import com.theodomart.energy.security.service.IssuedToken;
import java.time.Instant;

/** What a successful login or refresh hands back: a short-lived access token and its long-lived refresh token. */
public record AuthTokens(IssuedToken accessToken, String refreshToken, Instant refreshTokenIssuedAt,
		Instant refreshTokenExpiresAt) {

	@Override
	public String toString() {
		return "AuthTokens[accessToken=" + accessToken + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt + "]";
	}

}
