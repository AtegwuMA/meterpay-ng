package com.theodomart.energy.meter.service;

import com.theodomart.energy.security.service.CurrentUser;
import java.util.List;
import java.util.UUID;

public interface MeterService {

	/** @throws com.theodomart.energy.common.error.DuplicateResourceException if the serial number is already registered */
	MeterView register(UUID landlordId, RegisterMeterCommand command);

	/**
	 * Installs the landlord's meter on the landlord's unit. Someone else's meter or unit is a 404.
	 *
	 * @throws com.theodomart.energy.common.error.DuplicateResourceException if the unit already has a meter or
	 * the meter is already on another unit
	 */
	MeterView assignToUnit(UUID landlordId, UUID meterId, UUID unitId);

	/** Readable by the owning landlord and the unit's current tenant. Anyone else gets a 404. */
	MeterView get(CurrentUser user, UUID meterId);

	/**
	 * Someone else's meter is a 404.
	 *
	 * @throws com.theodomart.energy.common.error.IllegalStateTransitionException if it is already suspended or is
	 * decommissioned
	 */
	MeterView suspend(UUID landlordId, UUID meterId);

	/**
	 * Returns the meter to the state it was suspended from. Someone else's meter is a 404.
	 *
	 * @throws com.theodomart.energy.common.error.IllegalStateTransitionException if it is not suspended
	 */
	MeterView resume(UUID landlordId, UUID meterId);

	/** The tenant's current tenancies, newest first, each with the unit's meter if it has one. */
	List<TenantTenancyView> listTenancies(UUID tenantId);

}
