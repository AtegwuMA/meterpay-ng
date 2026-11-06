package com.theodomart.energy.tariff.config;

import com.theodomart.energy.tariff.domain.TariffVersion;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The platform fee is the platform's rate, not the landlord's (DESIGN 6.2). It is copied onto each new version, so
 * changing it here never reprices an existing version.
 */
@ConfigurationProperties("meterpay.tariff")
public record TariffProperties(int platformFeeBasisPoints) {

	public TariffProperties {
		// Fail at startup, not at the first tariff a landlord tries to create.
		if (platformFeeBasisPoints < 0 || platformFeeBasisPoints > TariffVersion.MAX_PLATFORM_FEE_BASIS_POINTS) {
			throw new IllegalStateException("meterpay.tariff.platform-fee-basis-points must be between 0 and "
					+ TariffVersion.MAX_PLATFORM_FEE_BASIS_POINTS);
		}
	}

}
