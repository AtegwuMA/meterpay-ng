package com.theodomart.energy.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("meterpay.identity")
public record IdentityProperties(Duration refreshTokenTtl) {

	public IdentityProperties {
		if (refreshTokenTtl == null || refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
			throw new IllegalStateException("meterpay.identity.refresh-token-ttl must be positive");
		}
	}

}
