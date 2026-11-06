package com.theodomart.energy.tariff.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.theodomart.energy.security.service.OwnershipService;
import com.theodomart.energy.tariff.config.TariffProperties;
import com.theodomart.energy.tariff.domain.TariffPlan;
import com.theodomart.energy.tariff.domain.TariffVersion;
import com.theodomart.energy.tariff.domain.TaxRate;
import com.theodomart.energy.tariff.repository.TariffPlanRepository;
import com.theodomart.energy.tariff.repository.TariffVersionRepository;
import com.theodomart.energy.tariff.repository.TaxRateRepository;
import com.theodomart.energy.tariff.service.ActivePricing;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real clock cannot be stopped either side of midnight in an integration test, so these use a scripted one. */
class TariffServiceImplTest {

	private static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-12-31T22:59:59.999999Z");

	private static final Instant AFTER_MIDNIGHT = Instant.parse("2026-12-31T23:00:00Z");

	private final UUID buildingId = UUID.randomUUID();

	private final UUID planId = UUID.randomUUID();

	private final TariffPlanRepository plans = mock();

	private final TariffVersionRepository versions = mock();

	private final TaxRateRepository taxRates = mock();

	private final Clock clock = mock();

	private final TariffPlan plan = mock();

	private final TariffServiceImpl service = new TariffServiceImpl(plans, versions, taxRates,
			mock(OwnershipService.class), new TariffProperties(200), clock);

	@BeforeEach
	void buildingWithAPlan() {
		when(clock.instant()).thenReturn(BEFORE_MIDNIGHT, AFTER_MIDNIGHT);
		when(plan.getId()).thenReturn(planId);
		when(plan.getBuildingId()).thenReturn(buildingId);
		when(plan.getName()).thenReturn("Standard");
		when(plans.findByBuildingId(buildingId)).thenReturn(Optional.of(plan));
	}

	@Test
	void priceAndVatAreBothReadAtTheSameMoment() {
		TariffVersion version = TariffVersion.create(planId, 22_500, 200, null, BEFORE_MIDNIGHT);
		when(versions.findFirstByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(planId, BEFORE_MIDNIGHT))
			.thenReturn(Optional.of(version));
		vatInForceAt(BEFORE_MIDNIGHT, 750);

		ActivePricing pricing = service.findActivePricing(buildingId).orElseThrow();

		assertThat(pricing.vatBasisPoints()).isEqualTo(750);
		assertThat(pricing.version().id()).isEqualTo(version.getId());
		assertThat(pricing.version().active()).isTrue();
		verify(taxRates, never()).findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(AFTER_MIDNIGHT);
		verify(clock).instant();
	}

	@Test
	void aBuildingWithNoPlanHasNoPricing() {
		when(plans.findByBuildingId(buildingId)).thenReturn(Optional.empty());

		assertThat(service.findActivePricing(buildingId)).isEmpty();
		verifyNoInteractions(taxRates);
	}

	@Test
	void aBuildingWithOnlyAFutureVersionHasNoPricing() {
		when(versions.findFirstByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any(), any()))
			.thenReturn(Optional.empty());

		assertThat(service.findActivePricing(buildingId)).isEmpty();
		verifyNoInteractions(taxRates);
	}

	@Test
	void missingVatRateFailsInsteadOfPricingWithoutTax() {
		when(versions.findFirstByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any(), any()))
			.thenReturn(Optional.of(TariffVersion.create(planId, 22_500, 200, null, BEFORE_MIDNIGHT)));
		when(taxRates.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findActivePricing(buildingId)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("VAT");
	}

	private void vatInForceAt(Instant at, int basisPoints) {
		TaxRate rate = mock();
		when(rate.getVatBasisPoints()).thenReturn(basisPoints);
		when(taxRates.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(at)).thenReturn(Optional.of(rate));
	}

}
