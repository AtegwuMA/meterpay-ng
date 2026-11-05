package com.theodomart.energy.property.service;

import java.time.LocalDate;
import java.util.UUID;

/** Status is a string because other modules will read this view and may not depend on property.domain. */
public record TenancyView(UUID id, UUID unitId, UUID tenantId, LocalDate startsOn, LocalDate endsOn, String status) {

}
