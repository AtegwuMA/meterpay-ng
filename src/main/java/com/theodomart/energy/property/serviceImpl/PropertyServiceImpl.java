package com.theodomart.energy.property.serviceImpl;

import com.theodomart.energy.common.error.DuplicateResourceException;
import com.theodomart.energy.common.error.InvalidRequestException;
import com.theodomart.energy.common.error.ResourceNotFoundException;
import com.theodomart.energy.common.persistence.ConstraintViolations;
import com.theodomart.energy.property.domain.Building;
import com.theodomart.energy.property.domain.Unit;
import com.theodomart.energy.property.repository.BuildingRepository;
import com.theodomart.energy.property.repository.UnitRepository;
import com.theodomart.energy.property.service.BuildingDetails;
import com.theodomart.energy.property.service.BuildingView;
import com.theodomart.energy.property.service.PropertyService;
import com.theodomart.energy.property.service.UnitView;
import com.theodomart.energy.security.service.OwnershipService;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyServiceImpl implements PropertyService {

	private static final Logger log = LoggerFactory.getLogger(PropertyServiceImpl.class);

	// Stable order across pages: name alone could tie, and ties may come back in any order between requests.
	private static final Sort BUILDING_ORDER = Sort.by("name").and(Sort.by("id"));

	private static final Sort UNIT_ORDER = Sort.by("label").and(Sort.by("id"));

	private final BuildingRepository buildings;

	private final UnitRepository units;

	private final OwnershipService ownership;

	private final Clock clock;

	public PropertyServiceImpl(BuildingRepository buildings, UnitRepository units, OwnershipService ownership,
			Clock clock) {
		this.buildings = buildings;
		this.units = units;
		this.ownership = ownership;
		this.clock = clock;
	}

	@Override
	@Transactional
	public BuildingView createBuilding(UUID landlordId, BuildingDetails details) {
		Building building = buildings.save(Building.create(landlordId, details.name(), details.address(),
				details.state(), details.lga(), clock.instant()));
		log.info("Building created: buildingId={} landlordId={}", building.getId(), landlordId);
		return toView(building);
	}

	@Override
	@Transactional(readOnly = true)
	public Page<BuildingView> listBuildings(UUID landlordId, int page, int size) {
		return buildings.findByLandlordId(landlordId, PageRequest.of(page, size, BUILDING_ORDER))
			.map(PropertyServiceImpl::toView);
	}

	@Override
	@Transactional(readOnly = true)
	public BuildingView getBuilding(UUID landlordId, UUID buildingId) {
		return toView(ownedBuilding(landlordId, buildingId));
	}

	@Override
	@Transactional
	public BuildingView updateBuilding(UUID landlordId, UUID buildingId, BuildingDetails changes) {
		Building building = ownedBuilding(landlordId, buildingId);
		try {
			building.update(changes.name(), changes.address(), changes.state(), changes.lga(), clock.instant());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage() + ".");
		}
		// Flush now so a concurrent edit surfaces here as an optimistic lock failure (409), and the response
		// carries the committed updatedAt.
		buildings.flush();
		return toView(building);
	}

	@Override
	@Transactional
	public UnitView addUnit(UUID landlordId, UUID buildingId, String label) {
		Building building = ownedBuilding(landlordId, buildingId);
		Unit unit = Unit.create(building.getId(), label, clock.instant());
		try {
			// The unique index decides, not a lookup first: two concurrent requests would both pass a lookup.
			units.saveAndFlush(unit);
		}
		catch (DataIntegrityViolationException ex) {
			if ("uk_units_building_label".equals(ConstraintViolations.constraintName(ex))) {
				throw new DuplicateResourceException("A unit labelled \"" + unit.getLabel()
						+ "\" already exists in this building.");
			}
			throw ex;
		}
		return toView(unit);
	}

	@Override
	@Transactional(readOnly = true)
	public Page<UnitView> listUnits(UUID landlordId, UUID buildingId, int page, int size) {
		Building building = ownedBuilding(landlordId, buildingId);
		return units.findByBuildingId(building.getId(), PageRequest.of(page, size, UNIT_ORDER))
			.map(PropertyServiceImpl::toView);
	}

	private Building ownedBuilding(UUID landlordId, UUID buildingId) {
		ownership.requireBuildingOwner(landlordId, buildingId);
		return buildings.findById(buildingId)
			.orElseThrow(() -> new ResourceNotFoundException("Building " + buildingId + " was not found."));
	}

	private static BuildingView toView(Building b) {
		return new BuildingView(b.getId(), b.getName(), b.getAddress(), b.getState(), b.getLga(), b.getCreatedAt(),
				b.getUpdatedAt());
	}

	private static UnitView toView(Unit u) {
		return new UnitView(u.getId(), u.getBuildingId(), u.getLabel(), u.getCreatedAt());
	}

}
