package com.theodomart.energy.tariff.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.common.domain.Energy;
import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.tariff.service.ActivePricing;
import com.theodomart.energy.tariff.service.PriceBreakdown;
import com.theodomart.energy.tariff.service.TariffVersionView;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PricingCalculatorImplTest {

	private static final Instant START = Instant.parse("2026-11-06T09:00:00Z");

	private final PricingCalculatorImpl calculator = new PricingCalculatorImpl();

	@Test
	void matchesTheWorkedExampleInTheDesign() {
		PriceBreakdown price = calculator.price(Money.ofKobo(200_000), pricing(22_500, 750, 200));

		assertThat(price.gross()).isEqualTo(Money.ofKobo(200_000));
		assertThat(price.vat()).isEqualTo(Money.ofKobo(13_953));
		assertThat(price.fee()).isEqualTo(Money.ofKobo(3_720));
		assertThat(price.net()).isEqualTo(Money.ofKobo(182_327));
		assertThat(price.energy()).isEqualTo(Energy.ofWh(8_103));
	}

	// The expected values were worked out by hand from DESIGN 6.4, not by running the code.
	@ParameterizedTest(name = "{0}: {1} kobo")
	@CsvSource(delimiter = '|', textBlock = """
			# case                                 | gross     | rate  | vatBp | feeBp | vat     | fee     | net      | wh
			VAT rounds up past a half, fee down    | 1000      | 22500 | 750   | 200   | 70      | 18      | 912      | 40
			every division is exact                | 10750     | 1000  | 750   | 200   | 750     | 200     | 9800     | 9800
			one million naira                      | 100000000 | 22500 | 750   | 200   | 6976744 | 1860465 | 91162791 | 4051679
			no VAT and no fee                      | 100000    | 22500 | 0     | 0     | 0       | 0       | 100000   | 4444
			VAT only                               | 10750     | 1000  | 750   | 0     | 750     | 0       | 10000    | 10000
			fee only                               | 10000     | 1000  | 0     | 200   | 0       | 200     | 9800     | 9800
			highest VAT takes half                 | 200000    | 1000  | 10000 | 0     | 100000  | 0       | 100000   | 100000
			highest fee leaves one kobo            | 10000     | 1000  | 0     | 9999  | 0       | 9999    | 1        | 1
			last amount with no VAT                | 7         | 22500 | 750   | 200   | 0       | 0       | 7        | 0
			first amount with VAT                  | 8         | 22500 | 750   | 200   | 1       | 0       | 7        | 0
			last amount with no fee                | 53        | 22500 | 750   | 200   | 4       | 0       | 49       | 2
			first amount with a fee                | 54        | 22500 | 750   | 200   | 4       | 1       | 49       | 2
			largest amount that buys no energy     | 24        | 22500 | 750   | 200   | 2       | 0       | 22       | 0
			smallest amount that buys energy       | 25        | 22500 | 750   | 200   | 2       | 0       | 23       | 1
			an exact half of VAT rounds up         | 3         | 1000  | 10000 | 0     | 2       | 0       | 1        | 1
			one kobo at 100 percent VAT is all VAT | 1         | 1000  | 10000 | 0     | 1       | 0       | 0        | 0
			one kobo buys nothing above 1000 rate  | 1        | 1001  | 0     | 0     | 0       | 0       | 1        | 0
			one kobo buys one Wh at 1000 rate      | 1         | 1000  | 0     | 0     | 0       | 0       | 1        | 1
			lowest rate gives 1000 Wh per kobo     | 1         | 1     | 0     | 0     | 0       | 0       | 1        | 1000
			energy rounds down, not to nearest     | 2         | 3     | 0     | 0     | 0       | 0       | 2        | 666
			""")
	void splitsTheAmountByTheDesignRounding(String description, long gross, long rate, int vatBp, int feeBp, long vat,
			long fee, long net, long wh) {
		PriceBreakdown price = calculator.price(Money.ofKobo(gross), pricing(rate, vatBp, feeBp));

		assertThat(price.vat().kobo()).as("vat").isEqualTo(vat);
		assertThat(price.fee().kobo()).as("fee").isEqualTo(fee);
		assertThat(price.net().kobo()).as("net").isEqualTo(net);
		assertThat(price.energy().wh()).as("energy").isEqualTo(wh);
	}

	@ParameterizedTest
	@CsvSource({ "22500, 750, 200", "1, 0, 0", "7, 10000, 9999", "99999, 1, 1", "1000, 750, 9999" })
	void partsAlwaysAddUpAndEachDivisionRoundsByItsRule(long rate, int vatBp, int feeBp) {
		ActivePricing pricing = pricing(rate, vatBp, feeBp);

		for (long gross = 1; gross <= 20_000; gross++) {
			PriceBreakdown price = calculator.price(Money.ofKobo(gross), pricing);
			long vat = price.vat().kobo();
			long afterVat = gross - vat;
			long fee = price.fee().kobo();
			long net = price.net().kobo();
			long wh = price.energy().wh();

			// A kobo that goes missing here would leave the ledger entry for this purchase unbalanced.
			assertThat(vat + fee + net).as("parts of %d", gross).isEqualTo(gross);
			assertThat(net).as("net of %d", gross).isGreaterThanOrEqualTo(gross == 1 ? 0 : 1);

			// VAT is the nearest whole kobo, with a half going up: exact <= vat + 1/2 is 2 x exact < 2 x vat + 1 etc.
			long divisor = 10_000 + vatBp;
			assertThat(2 * vat * divisor).as("vat of %d", gross).isLessThanOrEqualTo(2 * gross * vatBp + divisor);
			assertThat(2 * (vat + 1) * divisor).as("vat of %d", gross).isGreaterThan(2 * gross * vatBp + divisor);

			// The fee and energy are the largest whole numbers that do not overcharge or over-deliver.
			assertThat(fee * 10_000).as("fee of %d", gross).isLessThanOrEqualTo(afterVat * feeBp);
			assertThat((fee + 1) * 10_000).as("fee of %d", gross).isGreaterThan(afterVat * feeBp);
			assertThat(wh * rate).as("energy of %d", gross).isLessThanOrEqualTo(net * 1_000);
			assertThat((wh + 1) * rate).as("energy of %d", gross).isGreaterThan(net * 1_000);
		}
	}

	@ParameterizedTest
	@CsvSource({ "22500, 750, 200", "1000, 750, 9999", "7, 10000, 0" })
	void payingMoreNeverDeliversLessEnergy(long rate, int vatBp, int feeBp) {
		ActivePricing pricing = pricing(rate, vatBp, feeBp);
		long previous = 0;

		for (long gross = 1; gross <= 20_000; gross++) {
			long wh = calculator.price(Money.ofKobo(gross), pricing).energy().wh();

			assertThat(wh).as("energy for %d kobo", gross).isGreaterThanOrEqualTo(previous);
			previous = wh;
		}
	}

	@Test
	void theSameInputAlwaysGivesTheSamePrice() {
		ActivePricing pricing = pricing(22_500, 750, 200);

		assertThat(calculator.price(Money.ofKobo(200_000), pricing))
			.isEqualTo(calculator.price(Money.ofKobo(200_000), pricing));
	}

	@Test
	void aPurchaseOfZeroIsRefused() {
		assertThatThrownBy(() -> calculator.price(Money.ZERO, pricing(22_500, 750, 200)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("zero");
	}

	@ParameterizedTest
	@CsvSource({ "750, 200", "0, 200", "0, 0" })
	void anAmountTooLargeToCalculateFailsInsteadOfWrappingAround(int vatBp, int feeBp) {
		ActivePricing pricing = pricing(22_500, vatBp, feeBp);

		assertThatThrownBy(() -> calculator.price(Money.ofKobo(Long.MAX_VALUE), pricing))
			.isInstanceOf(ArithmeticException.class);
	}

	private static ActivePricing pricing(long rate, int vatBasisPoints, int feeBasisPoints) {
		TariffVersionView version = new TariffVersionView(UUID.randomUUID(), UUID.randomUUID(), "Standard", rate,
				feeBasisPoints, START, true, START);
		return new ActivePricing(version, vatBasisPoints);
	}

}
