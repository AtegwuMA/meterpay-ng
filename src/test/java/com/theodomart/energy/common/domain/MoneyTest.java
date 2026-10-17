package com.theodomart.energy.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

	@Test
	void holdsAmountInKobo() {
		assertThat(Money.ofKobo(150).kobo()).isEqualTo(150);
		assertThat(Money.ofNaira(5_000).kobo()).isEqualTo(500_000);
		assertThat(Money.ZERO.isZero()).isTrue();
	}

	@Test
	void rejectsNegativeAmounts() {
		assertThatThrownBy(() -> Money.ofKobo(-1)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("-1 kobo");
		assertThatThrownBy(() -> new Money(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Money.ofNaira(-1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNairaAmountsThatOverflowWhenConvertedToKobo() {
		assertThatThrownBy(() -> Money.ofNaira(Long.MAX_VALUE / 100 + 1)).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void addsExactly() {
		assertThat(Money.ofKobo(13_953).add(Money.ofKobo(3_720)).add(Money.ofKobo(182_327)))
			.isEqualTo(Money.ofKobo(200_000));
	}

	@Test
	void additionThatOverflowsFailsInsteadOfWrapping() {
		Money max = Money.ofKobo(Long.MAX_VALUE);

		assertThatThrownBy(() -> max.add(Money.ofKobo(1))).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void subtractsExactly() {
		assertThat(Money.ofKobo(500_000).subtract(Money.ofKobo(200_000))).isEqualTo(Money.ofKobo(300_000));
		assertThat(Money.ofKobo(200_000).subtract(Money.ofKobo(200_000))).isEqualTo(Money.ZERO);
	}

	@Test
	void subtractionThatWouldGoNegativeFails() {
		Money balance = Money.ofKobo(120_000);
		Money price = Money.ofKobo(200_000);

		assertThatThrownBy(() -> balance.subtract(price)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("negative");
	}

	@Test
	void comparesByAmount() {
		Money small = Money.ofKobo(120_000);
		Money large = Money.ofKobo(200_000);

		assertThat(small.isLessThan(large)).isTrue();
		assertThat(large.isLessThan(small)).isFalse();
		assertThat(small.isLessThan(Money.ofKobo(120_000))).isFalse();
		assertThat(small).isLessThan(large);
	}

	@Test
	void equalAmountsAreEqual() {
		assertThat(Money.ofNaira(20)).isEqualTo(Money.ofKobo(2_000)).hasSameHashCodeAs(Money.ofKobo(2_000));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			0                   | ₦0.00
			5                   | ₦0.05
			100                 | ₦1.00
			120000              | ₦1,200.00
			200000              | ₦2,000.00
			123456789           | ₦1,234,567.89
			9223372036854775807 | ₦92,233,720,368,547,758.07
			""")
	void formatsAsNaira(long kobo, String expected) {
		assertThat(Money.ofKobo(kobo).format()).isEqualTo(expected);
	}

}
