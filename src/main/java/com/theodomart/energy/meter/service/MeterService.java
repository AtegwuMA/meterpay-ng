package com.theodomart.energy.meter.service;

import com.theodomart.energy.security.service.CurrentUser;
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

}
