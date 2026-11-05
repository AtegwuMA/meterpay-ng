package com.theodomart.energy.property.serviceImpl;

import com.theodomart.energy.property.repository.BuildingRepository;
import com.theodomart.energy.property.service.BuildingOwnerLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuildingOwnerLookupImpl implements BuildingOwnerLookup {

	private final BuildingRepository buildings;

	public BuildingOwnerLookupImpl(BuildingRepository buildings) {
		this.buildings = buildings;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> landlordOf(UUID buildingId) {
		return buildings.findLandlordIdById(buildingId);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> landlordOfUnit(UUID unitId) {
		return buildings.findLandlordIdByUnitId(unitId);
	}

}
