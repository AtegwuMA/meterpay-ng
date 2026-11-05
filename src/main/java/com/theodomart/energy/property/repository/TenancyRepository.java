package com.theodomart.energy.property.repository;

import com.theodomart.energy.property.domain.Tenancy;
import com.theodomart.energy.property.domain.TenancyStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenancyRepository extends JpaRepository<Tenancy, UUID> {

	Optional<Tenancy> findByUnitIdAndStatus(UUID unitId, TenancyStatus status);

	boolean existsByUnitIdAndStatus(UUID unitId, TenancyStatus status);

}
