package com.theodomart.energy.tariff.repository;

import com.theodomart.energy.tariff.domain.TariffPlan;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface TariffPlanRepository extends Repository<TariffPlan, UUID> {

	Optional<TariffPlan> findByBuildingId(UUID buildingId);

	/**
	 * ON CONFLICT rather than catching the violation: in Postgres a failed insert aborts the whole transaction, and
	 * the version still has to be saved in this one. A lookup first would let two first requests both try to insert.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO tariff_plans (id, building_id, name, created_at)
			VALUES (:id, :buildingId, :name, :createdAt)
			ON CONFLICT (building_id) DO NOTHING
			""", nativeQuery = true)
	void insertIfAbsent(@Param("id") UUID id, @Param("buildingId") UUID buildingId, @Param("name") String name,
			@Param("createdAt") Instant createdAt);

}
