package com.theodomart.energy.property.repository;

import com.theodomart.energy.property.domain.Tenancy;
import com.theodomart.energy.property.domain.TenancyStatus;
import com.theodomart.energy.property.service.ActiveTenancy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TenancyRepository extends JpaRepository<Tenancy, UUID> {

	Optional<Tenancy> findByUnitIdAndStatus(UUID unitId, TenancyStatus status);

	boolean existsByUnitIdAndStatus(UUID unitId, TenancyStatus status);

	boolean existsByUnitIdAndTenantIdAndStatus(UUID unitId, UUID tenantId, TenancyStatus status);

	/** One query for the whole list, newest first, so a tenant with several flats costs no more than one with one. */
	@Query("""
			SELECT new com.theodomart.energy.property.service.ActiveTenancy(
				t.id, b.id, b.name, u.id, u.label, t.startsOn)
			FROM Tenancy t
			JOIN Unit u ON u.id = t.unitId
			JOIN Building b ON b.id = u.buildingId
			WHERE t.tenantId = :tenantId AND t.status = :status
			ORDER BY t.startsOn DESC, t.id
			""")
	List<ActiveTenancy> findWithNamesByTenantId(UUID tenantId, TenancyStatus status);

}
