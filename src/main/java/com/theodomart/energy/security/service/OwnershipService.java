package com.theodomart.energy.security.service;

import java.util.UUID;

/**
 * The one place that decides whether a user owns a resource (DESIGN 11). Services call it, not only
 * controllers, so a new code path cannot forget the check by arriving through a different endpoint.
 */
public interface OwnershipService {

	/**
	 * @throws com.theodomart.energy.common.error.ResourceNotFoundException if the building does not exist or
	 * belongs to someone else, with the same message for both so ids cannot be probed (DESIGN 10)
	 */
	void requireBuildingOwner(UUID userId, UUID buildingId);

	/** Same rules as {@link #requireBuildingOwner}, for the building a unit is in. */
	void requireUnitOwner(UUID userId, UUID unitId);

}
