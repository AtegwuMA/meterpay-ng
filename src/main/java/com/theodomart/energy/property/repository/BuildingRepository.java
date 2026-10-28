package com.theodomart.energy.property.repository;

import com.theodomart.energy.property.domain.Building;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildingRepository extends JpaRepository<Building, UUID> {

	Page<Building> findByLandlordId(UUID landlordId, Pageable pageable);

	/**
	 * Scoped to the landlord in the query itself, so another landlord's building is simply not found: there
	 * is no window where it is loaded and then rejected.
	 */
	Optional<Building> findByIdAndLandlordId(UUID id, UUID landlordId);

}
