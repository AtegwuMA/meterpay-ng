package com.theodomart.energy.tariff.service;

import com.theodomart.energy.common.domain.Money;

public interface PricingCalculator {

	/**
	 * Splits what the tenant pays into VAT, the platform fee and the landlord's share, and works out the energy that
	 * share buys (DESIGN 6.4). Reads nothing and changes nothing, so the same inputs always give the same price.
	 *
	 * <p>The energy can be zero, and VAT or the fee can be zero, for a very small amount. Callers decide what to do
	 * about that.
	 *
	 * @throws IllegalArgumentException if gross is zero: there is nothing to price
	 * @throws ArithmeticException if the amount is too large to calculate exactly
	 */
	PriceBreakdown price(Money gross, TariffVersionView version);

}
