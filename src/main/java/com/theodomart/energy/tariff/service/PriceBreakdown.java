package com.theodomart.energy.tariff.service;

import com.theodomart.energy.common.domain.Energy;
import com.theodomart.energy.common.domain.Money;

/** How one purchase splits up. {@code vat + fee + net} always equals {@code gross}, to the kobo. */
public record PriceBreakdown(Money gross, Money vat, Money fee, Money net, Energy energy) {

}
