package com.theodomart.energy.identity.api;

import com.theodomart.energy.identity.service.AuthTokens;
import java.time.Duration;

/** OAuth 2.0 style field meanings (RFC 6749, section 5.1) in camelCase. Both lifetimes are in seconds. */
public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
		long refreshTokenExpiresIn) {

	static TokenResponse from(AuthTokens tokens) {
		// Each lifetime comes from its own token's timestamps. Mixing in another clock read (or the other
		// token's issue time) drifts by milliseconds and reports 899 for a 900 second token.
		var access = tokens.accessToken();
		return new TokenResponse(access.value(), "Bearer",
				Duration.between(access.issuedAt(), access.expiresAt()).toSeconds(), tokens.refreshToken(),
				Duration.between(tokens.refreshTokenIssuedAt(), tokens.refreshTokenExpiresAt()).toSeconds());
	}

	@Override
	public String toString() {
		return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
	}

}
