package com.theodomart.energy.tariff.service;

import java.time.Instant;
import java.util.UUID;

/** {@code active} is true for the one version in force when this view was made. */
public record TariffVersionView(UUID id, UUID buildingId, String planName, long rateKoboPerKwh, int vatBasisPoints,
		int platformFeeBasisPoints, Instant effectiveFrom, boolean active, Instant createdAt) {

}
