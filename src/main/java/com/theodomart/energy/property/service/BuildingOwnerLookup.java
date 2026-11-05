package com.theodomart.energy.property.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Lets the security module ask who owns a building without touching this module's repository (ArchUnit
 * rule 2). Separate from PropertyService, which itself calls OwnershipService: one combined interface would
 * make the two beans depend on each other.
 */
public interface BuildingOwnerLookup {

	Optional<UUID> landlordOf(UUID buildingId);

	/** The landlord of the building this unit is in. */
	Optional<UUID> landlordOfUnit(UUID unitId);

}
