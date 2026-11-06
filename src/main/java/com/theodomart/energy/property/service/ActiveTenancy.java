package com.theodomart.energy.property.service;

import java.time.LocalDate;
import java.util.UUID;

/** A tenant's current tenancy, with the names a person needs to recognise it. */
public record ActiveTenancy(UUID tenancyId, UUID buildingId, String buildingName, UUID unitId, String unitLabel,
		LocalDate startsOn) {

}
