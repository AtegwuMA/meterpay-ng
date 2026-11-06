package com.theodomart.energy.tariff.service;

import java.time.Instant;

/**
 * @param planName names the plan when this is the building's first version; ignored after that, because plans do
 * not change. Null means the default name.
 * @param effectiveFrom when the price starts. Null means now.
 */
public record NewTariffVersion(long rateKoboPerKwh, Instant effectiveFrom, String planName) {

}
