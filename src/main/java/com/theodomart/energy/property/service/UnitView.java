package com.theodomart.energy.property.service;

import java.time.Instant;
import java.util.UUID;

public record UnitView(UUID id, UUID buildingId, String label, Instant createdAt) {

}
