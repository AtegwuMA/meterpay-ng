package com.theodomart.energy.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

	private static final String GOOD_SECRET = "0123456789abcdef0123456789abcdef";

	@Test
	void acceptsSecretOfExactly32Bytes() {
		assertThat(new JwtProperties(GOOD_SECRET, Duration.ofMinutes(15), "meterpay").secret()).hasSize(32);
	}

	@Test
	void refusesMissingOrShortSecretSoTheAppCannotStartWithAWeakKey() {
		assertThatThrownBy(() -> new JwtProperties(null, Duration.ofMinutes(15), "meterpay"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("JWT_SECRET");
		assertThatThrownBy(() -> new JwtProperties("", Duration.ofMinutes(15), "meterpay"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new JwtProperties(GOOD_SECRET.substring(1), Duration.ofMinutes(15), "meterpay"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void refusesNonPositiveTtlAndBlankIssuer() {
		assertThatThrownBy(() -> new JwtProperties(GOOD_SECRET, Duration.ZERO, "meterpay"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new JwtProperties(GOOD_SECRET, null, "meterpay"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new JwtProperties(GOOD_SECRET, Duration.ofMinutes(15), " "))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void toStringNeverPrintsTheSecret() {
		assertThat(new JwtProperties(GOOD_SECRET, Duration.ofMinutes(15), "meterpay").toString())
			.doesNotContain(GOOD_SECRET);
	}

}
