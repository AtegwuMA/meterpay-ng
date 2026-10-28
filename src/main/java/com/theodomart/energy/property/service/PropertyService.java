package com.theodomart.energy.property.service;

import java.util.UUID;
import org.springframework.data.domain.Page;

/**
 * Buildings and units of one landlord. Every method takes the caller's id: a building that belongs to anyone
 * else behaves exactly like one that does not exist (404, DESIGN 10).
 */
public interface PropertyService {

	BuildingView createBuilding(UUID landlordId, BuildingDetails details);

	Page<BuildingView> listBuildings(UUID landlordId, int page, int size);

	BuildingView getBuilding(UUID landlordId, UUID buildingId);

	/** Null fields in {@code changes} are left as they are. */
	BuildingView updateBuilding(UUID landlordId, UUID buildingId, BuildingDetails changes);

	/** @throws com.theodomart.energy.common.error.DuplicateResourceException if the label is taken in the building */
	UnitView addUnit(UUID landlordId, UUID buildingId, String label);

	Page<UnitView> listUnits(UUID landlordId, UUID buildingId, int page, int size);

}
