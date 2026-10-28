package com.theodomart.energy.property.repository;

import com.theodomart.energy.property.domain.Unit;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitRepository extends JpaRepository<Unit, UUID> {

	Page<Unit> findByBuildingId(UUID buildingId, Pageable pageable);

}
