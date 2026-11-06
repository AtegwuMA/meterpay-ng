package com.theodomart.energy.tariff.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TariffPropertiesTest {

	@Test
	void acceptsTheFeeInTheDesignExample() {
		assertThat(new TariffProperties(200).platformFeeBasisPoints()).isEqualTo(200);
	}

	@ParameterizedTest
	@ValueSource(ints = { -1, 10_000 })
	void refusesAFeeThatWouldFailOnEveryNewVersion(int fee) {
		assertThatThrownBy(() -> new TariffProperties(fee)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("platform-fee");
	}

}
