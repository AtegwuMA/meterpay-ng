package com.theodomart.energy.property.repository;

import com.theodomart.energy.property.domain.Building;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BuildingRepository extends JpaRepository<Building, UUID> {

	Page<Building> findByLandlordId(UUID landlordId, Pageable pageable);

	/** Only the owner's id: ownership checks run on most requests and do not need the whole row. */
	@Query("select b.landlordId from Building b where b.id = :id")
	Optional<UUID> findLandlordIdById(@Param("id") UUID id);

	@Query("select b.landlordId from Building b, Unit u where u.buildingId = b.id and u.id = :unitId")
	Optional<UUID> findLandlordIdByUnitId(@Param("unitId") UUID unitId);

}
