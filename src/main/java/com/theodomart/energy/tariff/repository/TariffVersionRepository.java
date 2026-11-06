package com.theodomart.energy.tariff.repository;

import com.theodomart.energy.tariff.domain.TariffVersion;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** A plain Repository, not JpaRepository, so versions have no delete methods to call (DESIGN 5.2 rule 4). */
public interface TariffVersionRepository extends Repository<TariffVersion, UUID> {

	TariffVersion saveAndFlush(TariffVersion version);

	Page<TariffVersion> findByPlanId(UUID planId, Pageable pageable);

	/** The version in force at that moment: the latest one that has started. */
	Optional<TariffVersion> findFirstByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(UUID planId,
			Instant at);

}
