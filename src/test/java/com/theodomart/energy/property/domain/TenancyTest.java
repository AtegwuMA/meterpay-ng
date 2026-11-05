package com.theodomart.energy.property.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenancyTest {

	private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");

	private static final LocalDate START = LocalDate.of(2026, 11, 5);

	private final Tenancy tenancy = Tenancy.start(UUID.randomUUID(), UUID.randomUUID(), START, NOW);

	@Test
	void startsActiveWithNoEndDate() {
		assertThat(tenancy.getStatus()).isEqualTo(TenancyStatus.ACTIVE);
		assertThat(tenancy.getStartsOn()).isEqualTo(START);
		assertThat(tenancy.getEndsOn()).isNull();
	}

	@Test
	void endingRecordsTheDate() {
		tenancy.end(START.plusMonths(6), NOW);

		assertThat(tenancy.getStatus()).isEqualTo(TenancyStatus.ENDED);
		assertThat(tenancy.getEndsOn()).isEqualTo(START.plusMonths(6));
	}

	@Test
	void canEndOnTheDayItStarted() {
		tenancy.end(START, NOW);

		assertThat(tenancy.getEndsOn()).isEqualTo(START);
	}

	@Test
	void cannotEndBeforeItStartedOrEndTwice() {
		assertThatThrownBy(() -> tenancy.end(START.minusDays(1), NOW)).isInstanceOf(IllegalArgumentException.class);

		tenancy.end(START, NOW);

		assertThatThrownBy(() -> tenancy.end(START, NOW)).isInstanceOf(IllegalStateException.class);
	}

}
