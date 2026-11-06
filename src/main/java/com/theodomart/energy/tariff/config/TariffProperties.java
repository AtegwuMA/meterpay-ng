package com.theodomart.energy.tariff.config;

import com.theodomart.energy.tariff.domain.TariffVersion;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * VAT and the platform fee are the platform's rates, not the landlord's (DESIGN 6.2). They are copied onto each new
 * version, so changing them here never reprices an existing version.
 */
@ConfigurationProperties("meterpay.tariff")
public record TariffProperties(int vatBasisPoints, int platformFeeBasisPoints) {

	public TariffProperties {
		// Fail at startup, not at the first tariff a landlord tries to create.
		if (vatBasisPoints < 0 || vatBasisPoints > TariffVersion.MAX_VAT_BASIS_POINTS) {
			throw new IllegalStateException(
					"meterpay.tariff.vat-basis-points must be between 0 and " + TariffVersion.MAX_VAT_BASIS_POINTS);
		}
		if (platformFeeBasisPoints < 0 || platformFeeBasisPoints > TariffVersion.MAX_PLATFORM_FEE_BASIS_POINTS) {
			throw new IllegalStateException("meterpay.tariff.platform-fee-basis-points must be between 0 and "
					+ TariffVersion.MAX_PLATFORM_FEE_BASIS_POINTS);
		}
	}

}
