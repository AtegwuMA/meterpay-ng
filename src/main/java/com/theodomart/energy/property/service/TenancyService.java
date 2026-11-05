package com.theodomart.energy.property.service;

import java.util.UUID;

public interface TenancyService {

	/**
	 * Issues a fresh invite code for a vacant unit the landlord owns, cancelling any earlier open code.
	 *
	 * @throws UnitOccupiedException if the unit already has an active tenancy
	 */
	InviteView createInvite(UUID landlordId, UUID unitId);

	/**
	 * Uses up the code and starts the tenancy. Must run inside the caller's transaction, so that registering
	 * the tenant and redeeming the code succeed or fail together.
	 *
	 * @throws InvalidInviteCodeException for an unknown, used, cancelled or expired code, all alike
	 * @throws UnitOccupiedException if the unit became occupied in the meantime
	 */
	TenancyView redeemInvite(String code, UUID tenantId);

	/** @throws com.theodomart.energy.common.error.ResourceNotFoundException if no active tenancy exists */
	TenancyView endTenancy(UUID landlordId, UUID unitId);

}
