package com.theodomart.energy.property.service;

import java.time.Instant;
import java.util.UUID;

public record BuildingView(UUID id, String name, String address, String state, String lga, Instant createdAt,
		Instant updatedAt) {

}
