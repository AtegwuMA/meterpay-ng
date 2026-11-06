package com.theodomart.energy.tariff.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TariffPropertiesTest {

	@Test
	void acceptsTheRatesInTheDesignExample() {
		TariffProperties properties = new TariffProperties(750, 200);

		assertThat(properties.vatBasisPoints()).isEqualTo(750);
		assertThat(properties.platformFeeBasisPoints()).isEqualTo(200);
	}

	@ParameterizedTest
	@CsvSource({ "-1, 200, vat", "10001, 200, vat", "750, -1, platform-fee", "750, 10000, platform-fee" })
	void refusesRatesThatWouldFailOnEveryNewVersion(int vat, int fee, String property) {
		assertThatThrownBy(() -> new TariffProperties(vat, fee)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(property);
	}

}
