package com.theodomart.energy.security.serviceImpl;

import com.theodomart.energy.common.error.ResourceNotFoundException;
import com.theodomart.energy.property.service.BuildingOwnerLookup;
import com.theodomart.energy.security.service.OwnershipService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OwnershipServiceImpl implements OwnershipService {

	private static final Logger log = LoggerFactory.getLogger(OwnershipServiceImpl.class);

	private final BuildingOwnerLookup buildingOwners;

	public OwnershipServiceImpl(BuildingOwnerLookup buildingOwners) {
		this.buildingOwners = buildingOwners;
	}

	@Override
	public void requireBuildingOwner(UUID userId, UUID buildingId) {
		boolean owner = buildingOwners.landlordOf(buildingId).filter(userId::equals).isPresent();
		if (!owner) {
			// Logged, because repeated hits from one user can mean someone is probing ids. The client only ever
			// sees the plain 404.
			log.info("Building access refused: userId={} buildingId={}", userId, buildingId);
			throw new ResourceNotFoundException("Building " + buildingId + " was not found.");
		}
	}

	@Override
	public void requireUnitOwner(UUID userId, UUID unitId) {
		boolean owner = buildingOwners.landlordOfUnit(unitId).filter(userId::equals).isPresent();
		if (!owner) {
			log.info("Unit access refused: userId={} unitId={}", userId, unitId);
			throw new ResourceNotFoundException("Unit " + unitId + " was not found.");
		}
	}

}
