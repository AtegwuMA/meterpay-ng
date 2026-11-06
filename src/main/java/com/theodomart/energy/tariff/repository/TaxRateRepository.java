package com.theodomart.energy.tariff.repository;

import com.theodomart.energy.tariff.domain.TaxRate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Reads only. New rates are added by migration, so the application has no way to change the tax. */
public interface TaxRateRepository extends Repository<TaxRate, UUID> {

	/** The rate in force at that moment: the latest one that has started. */
	Optional<TaxRate> findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(Instant at);

}
