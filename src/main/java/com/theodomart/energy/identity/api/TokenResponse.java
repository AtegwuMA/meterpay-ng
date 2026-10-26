package com.theodomart.energy.identity.api;

/** OAuth 2.0 style field meanings (RFC 6749, section 5.1) in camelCase: expiresIn is in seconds. */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

	@Override
	public String toString() {
		return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
	}

}
