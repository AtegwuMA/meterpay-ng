package com.theodomart.energy.meter.service;

import java.time.LocalDate;
import java.util.UUID;

/** {@code meter} is null until the landlord installs one on the unit. */
public record TenantTenancyView(UUID tenancyId, UUID buildingId, String buildingName, UUID unitId, String unitLabel,
		LocalDate startsOn, TenantMeterView meter) {

	public record TenantMeterView(UUID id, String serialNumber, String status, long creditBalanceWh) {

	}

}
