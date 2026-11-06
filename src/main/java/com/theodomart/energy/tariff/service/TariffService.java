package com.theodomart.energy.tariff.service;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;

public interface TariffService {

	/**
	 * Sets a new price for the landlord's building, creating the building's plan on first use. Someone else's
	 * building is a 404.
	 *
	 * @throws com.theodomart.energy.common.error.InvalidRequestException if the rate is not positive or the effective
	 * date is in the past
	 * @throws com.theodomart.energy.common.error.DuplicateResourceException if a version already starts at that moment
	 */
	TariffVersionView addVersion(UUID landlordId, UUID buildingId, NewTariffVersion command);

	/** Newest effective date first, including versions that have not started. Someone else's building is a 404. */
	Page<TariffVersionView> listVersions(UUID landlordId, UUID buildingId, int page, int size);

	/**
	 * The building's latest version that has started and the VAT rate in force, read at one single moment so a
	 * purchase at midnight cannot mix yesterday's price with today's VAT. Empty if the building has no tariff yet, or
	 * only future-dated ones. No ownership check: purchases price tenants' energy with this, and tenants do not own
	 * buildings.
	 *
	 * @throws IllegalStateException if no VAT rate is in force: the migrations seed one, so this is a broken database
	 */
	Optional<ActivePricing> findActivePricing(UUID buildingId);

}
