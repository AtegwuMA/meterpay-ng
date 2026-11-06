package com.theodomart.energy.tariff.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TariffVersionTest {

	private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

	private final UUID plan = UUID.randomUUID();

	@Test
	void versionKeepsItsPriceAndRates() {
		Instant start = NOW.plusSeconds(3600);

		TariffVersion version = TariffVersion.create(plan, 22_500, 750, 200, start, NOW);

		assertThat(version.getId()).isNotNull();
		assertThat(version.getPlanId()).isEqualTo(plan);
		assertThat(version.getRateKoboPerKwh()).isEqualTo(22_500);
		assertThat(version.getVatBasisPoints()).isEqualTo(750);
		assertThat(version.getPlatformFeeBasisPoints()).isEqualTo(200);
		assertThat(version.getEffectiveFrom()).isEqualTo(start);
		assertThat(version.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void missingEffectiveDateMeansNow() {
		assertThat(TariffVersion.create(plan, 22_500, 750, 200, null, NOW).getEffectiveFrom()).isEqualTo(NOW);
	}

	@Test
	void effectiveDateOfExactlyNowIsAllowed() {
		assertThat(TariffVersion.create(plan, 22_500, 750, 200, NOW, NOW).getEffectiveFrom()).isEqualTo(NOW);
	}

	@Test
	void pastEffectiveDateIsRefused() {
		assertThatThrownBy(() -> TariffVersion.create(plan, 22_500, 750, 200, NOW.minusMillis(1), NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("past");
	}

	@Test
	void timesAreCutToTheMicrosecondsPostgresStores() {
		Instant nanos = Instant.parse("2026-11-06T09:00:00.123456789Z");

		TariffVersion version = TariffVersion.create(plan, 22_500, 750, 200, null, nanos);

		assertThat(version.getCreatedAt()).isEqualTo("2026-11-06T09:00:00.123456Z");
		assertThat(version.getEffectiveFrom()).isEqualTo(version.getCreatedAt());
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, -1 })
	void rateMustBePositiveBecauseEnergyIsDividedByIt(long rate) {
		assertThatThrownBy(() -> TariffVersion.create(plan, rate, 750, 200, null, NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Rate");
	}

	@ParameterizedTest
	@CsvSource({ "-1, 200, VAT", "10001, 200, VAT", "750, -1, fee", "750, 10000, fee" })
	void vatAndFeeOutsideTheirRangesAreRefused(int vat, int fee, String message) {
		assertThatThrownBy(() -> TariffVersion.create(plan, 22_500, vat, fee, null, NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(message);
	}

	@Test
	void theLimitsThemselvesAreAllowed() {
		TariffVersion version = TariffVersion.create(plan, 1, TariffVersion.MAX_VAT_BASIS_POINTS,
				TariffVersion.MAX_PLATFORM_FEE_BASIS_POINTS, null, NOW);

		assertThat(version.getVatBasisPoints()).isEqualTo(10_000);
		assertThat(version.getPlatformFeeBasisPoints()).isEqualTo(9_999);
		assertThat(TariffVersion.create(plan, 1, 0, 0, null, NOW).getVatBasisPoints()).isZero();
	}

	@Test
	void versionsAreEqualByIdOnly() {
		TariffVersion version = TariffVersion.create(plan, 22_500, 750, 200, null, NOW);
		TariffVersion samePrice = TariffVersion.create(plan, 22_500, 750, 200, null, NOW);

		assertThat(version).isEqualTo(version).hasSameHashCodeAs(version).isNotEqualTo(samePrice);
		assertThat(version.toString()).contains(version.getId().toString());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = "   ")
	void planWithoutANameGetsTheDefault(String name) {
		assertThat(TariffPlan.normaliseName(name)).isEqualTo(TariffPlan.DEFAULT_NAME);
	}

	@Test
	void planNameIsTrimmed() {
		assertThat(TariffPlan.normaliseName("  Residential ")).isEqualTo("Residential");
	}

}
