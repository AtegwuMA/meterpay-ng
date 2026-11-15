package com.theodomart.energy.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PaystackPropertiesTest {

	private static final Duration SECONDS = Duration.ofSeconds(3);

	@Test
	void aSecretKeyAndATimeoutForEachStepAreEnough() {
		PaystackProperties properties = new PaystackProperties("https://api.paystack.co", " sk_test_abc ", " ", SECONDS,
				SECONDS);

		assertThat(properties.secretKey()).isEqualTo("sk_test_abc");
		assertThat(properties.callbackUrl()).isNull();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "pk_test_public_key_by_mistake", "secret" })
	void withoutASecretKeyTheAppDoesNotStart(String key) {
		assertThatThrownBy(() -> new PaystackProperties("https://api.paystack.co", key, null, SECONDS, SECONDS))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("PAYSTACK_SECRET_KEY");
	}

	@Test
	void aBaseUrlIsRequired() {
		assertThatThrownBy(() -> new PaystackProperties(" ", "sk_test_abc", null, SECONDS, SECONDS))
			.hasMessageContaining("base-url");
	}

	@Test
	void timeoutsMustBePositive() {
		assertThatThrownBy(() -> new PaystackProperties("https://x", "sk_test_abc", null, Duration.ZERO, SECONDS))
			.hasMessageContaining("connect-timeout");
		assertThatThrownBy(() -> new PaystackProperties("https://x", "sk_test_abc", null, SECONDS, null))
			.hasMessageContaining("read-timeout");
	}

	@Test
	void printingThePropertiesNeverShowsTheSecretKey() {
		PaystackProperties properties = new PaystackProperties("https://api.paystack.co", "sk_test_abc",
				"https://app/cb", SECONDS, SECONDS);

		assertThat(properties.toString()).doesNotContain("sk_test_abc").contains("[redacted]").contains("https://app/cb");
	}

}
