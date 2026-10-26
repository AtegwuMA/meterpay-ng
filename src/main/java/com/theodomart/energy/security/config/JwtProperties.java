package com.theodomart.energy.security.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("meterpay.security.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, String issuer) {

	/** HS256 needs a key at least as long as its 256-bit output (RFC 7518, section 3.2). */
	static final int MIN_SECRET_BYTES = 32;

	public JwtProperties {
		// Fail at startup, not at the first login: an app that cannot sign tokens safely must not come up.
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("meterpay.security.jwt.secret (JWT_SECRET) must be at least "
					+ MIN_SECRET_BYTES + " bytes. Generate one with: openssl rand -base64 48");
		}
		if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
			throw new IllegalStateException("meterpay.security.jwt.access-token-ttl must be positive");
		}
		if (issuer == null || issuer.isBlank()) {
			throw new IllegalStateException("meterpay.security.jwt.issuer must be set");
		}
	}

	@Override
	public String toString() {
		// Never print the secret, even in a config dump.
		return "JwtProperties[accessTokenTtl=" + accessTokenTtl + ", issuer=" + issuer + "]";
	}

}
