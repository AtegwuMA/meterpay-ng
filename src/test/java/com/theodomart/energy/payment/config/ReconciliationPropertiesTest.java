package com.theodomart.energy.payment.config;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReconciliationPropertiesTest {

	private static final Duration FIVE = Duration.ofMinutes(5);

	private static final Duration TEN = Duration.ofMinutes(10);

	private static final Duration HOUR = Duration.ofMinutes(60);

	@Test
	void theDefaultsAreAccepted() {
		assertThatNoException().isThrownBy(() -> new ReconciliationProperties(FIVE, TEN, HOUR, 100));
	}

	@Test
	void everyDurationMustBePositive() {
		assertThatThrownBy(() -> new ReconciliationProperties(null, TEN, HOUR, 100))
			.hasMessageContaining("interval");
		assertThatThrownBy(() -> new ReconciliationProperties(FIVE, Duration.ZERO, HOUR, 100))
			.hasMessageContaining("stale-after");
		assertThatThrownBy(() -> new ReconciliationProperties(FIVE, TEN, Duration.ofMinutes(-1), 100))
			.hasMessageContaining("expire-after");
	}

	@Test
	void anIntentCannotExpireBeforeItIsEvenLookedAt() {
		assertThatThrownBy(() -> new ReconciliationProperties(FIVE, HOUR, TEN, 100))
			.hasMessageContaining("expire-after must not be shorter");
	}

	@Test
	void aRunLooksAtOneIntentAtLeast() {
		assertThatThrownBy(() -> new ReconciliationProperties(FIVE, TEN, HOUR, 0)).hasMessageContaining("batch-size");
	}

}
