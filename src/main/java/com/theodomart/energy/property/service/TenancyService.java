package com.theodomart.energy.property.service;

import java.util.List;
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

	/**
	 * For a tenant who already has an account: the same rules as {@link #redeemInvite}, in a transaction of its own.
	 * The tenant keeps their wallet, because it belongs to the user, not to a tenancy (DESIGN 17.2).
	 *
	 * @throws InvalidInviteCodeException for an unknown, used, cancelled or expired code, all alike
	 * @throws UnitOccupiedException if the unit became occupied in the meantime
	 */
	TenancyView joinUnit(UUID tenantId, String code);

	/** @throws com.theodomart.energy.common.error.ResourceNotFoundException if no active tenancy exists */
	TenancyView endTenancy(UUID landlordId, UUID unitId);

	/** Whether the user is the current tenant of the unit. A former tenant is not. */
	boolean isActiveTenant(UUID userId, UUID unitId);

	/** Newest first. Empty if the user is not currently a tenant anywhere. */
	List<ActiveTenancy> findActiveTenancies(UUID tenantId);

}
