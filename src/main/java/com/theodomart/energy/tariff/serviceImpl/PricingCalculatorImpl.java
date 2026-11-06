package com.theodomart.energy.tariff.serviceImpl;

import com.theodomart.energy.common.domain.Energy;
import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.tariff.service.PriceBreakdown;
import com.theodomart.energy.tariff.service.PricingCalculator;
import com.theodomart.energy.tariff.service.TariffVersionView;
import org.springframework.stereotype.Service;

@Service
public class PricingCalculatorImpl implements PricingCalculator {

	private static final long BASIS_POINTS = 10_000;

	private static final long WH_PER_KWH = 1_000;

	@Override
	public PriceBreakdown price(Money gross, TariffVersionView version) {
		if (gross.isZero()) {
			throw new IllegalArgumentException("Cannot price a purchase of zero");
		}
		long amount = gross.kobo();

		// The tenant's amount already includes VAT, so VAT is 750/10750 of it, not 7.5% of it.
		long vatBasisPoints = version.vatBasisPoints();
		long vat = Math.multiplyExact(amount, vatBasisPoints) / (BASIS_POINTS + vatBasisPoints);
		long afterVat = amount - vat;

		long fee = Math.multiplyExact(afterVat, version.platformFeeBasisPoints()) / BASIS_POINTS;

		// The landlord's share is what is left, not a third division, so the three parts always add up to gross.
		long net = afterVat - fee;

		// Multiply before dividing: net / rate first would round down to whole kWh, not whole Wh.
		long energyWh = Math.multiplyExact(net, WH_PER_KWH) / version.rateKoboPerKwh();

		return new PriceBreakdown(gross, Money.ofKobo(vat), Money.ofKobo(fee), Money.ofKobo(net),
				Energy.ofWh(energyWh));
	}

}
