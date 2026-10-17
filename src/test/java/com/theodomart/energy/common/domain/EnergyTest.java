package com.theodomart.energy.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EnergyTest {

	@Test
	void holdsAmountInWattHours() {
		assertThat(Energy.ofWh(8_103).wh()).isEqualTo(8_103);
		assertThat(Energy.ZERO.wh()).isZero();
	}

	@Test
	void allowsNegativeValuesForDeltasAndOvershoot() {
		Energy credit = Energy.ofWh(50);

		Energy afterConsumption = credit.subtract(Energy.ofWh(80));

		assertThat(afterConsumption).isEqualTo(Energy.ofWh(-30));
		assertThat(afterConsumption.isZeroOrNegative()).isTrue();
		assertThat(Energy.ZERO.subtract(Energy.ofWh(80))).isEqualTo(Energy.ofWh(-80));
	}

	@Test
	void addsAndSubtractsExactly() {
		assertThat(Energy.ofWh(1_000).add(Energy.ofWh(8_103))).isEqualTo(Energy.ofWh(9_103));
		assertThat(Energy.ofWh(9_103).subtract(Energy.ofWh(103))).isEqualTo(Energy.ofWh(9_000));
	}

	@Test
	void additionThatOverflowsFailsInsteadOfWrapping() {
		assertThatThrownBy(() -> Energy.ofWh(Long.MAX_VALUE).add(Energy.ofWh(1)))
			.isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> Energy.ofWh(Long.MIN_VALUE).add(Energy.ofWh(-1)))
			.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void subtractionThatOverflowsFailsInsteadOfWrapping() {
		assertThatThrownBy(() -> Energy.ofWh(Long.MIN_VALUE).subtract(Energy.ofWh(1)))
			.isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> Energy.ofWh(Long.MAX_VALUE).subtract(Energy.ofWh(-1)))
			.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void classifiesSignForCutoffDecisions() {
		assertThat(Energy.ofWh(1).isPositive()).isTrue();
		assertThat(Energy.ofWh(1).isZeroOrNegative()).isFalse();
		assertThat(Energy.ZERO.isPositive()).isFalse();
		assertThat(Energy.ZERO.isZeroOrNegative()).isTrue();
		assertThat(Energy.ofWh(-1).isPositive()).isFalse();
		assertThat(Energy.ofWh(-1).isZeroOrNegative()).isTrue();
	}

	@Test
	void comparesAndEqualsByAmount() {
		assertThat(Energy.ofWh(10)).isLessThan(Energy.ofWh(11)).isEqualTo(Energy.ofWh(10));
		assertThat(Energy.ofWh(10)).hasSameHashCodeAs(Energy.ofWh(10));
	}

}
