package com.theodomart.energy.ledger.service;

import java.util.Objects;
import java.util.UUID;

/**
 * Names an account the way callers know it: by type and owner, not by its id. Platform accounts have no owner.
 *
 * @param ownerId the user who owns the account, or null for a platform account
 */
public record AccountRef(AccountType type, UUID ownerId) {

	public AccountRef {
		Objects.requireNonNull(type, "type");
		if ((type.ownerType() == OwnerType.PLATFORM) != (ownerId == null)) {
			throw new IllegalArgumentException(type + (ownerId == null ? " needs an owner" : " has no owner"));
		}
	}

	public static AccountRef tenantWallet(UUID tenantId) {
		return new AccountRef(AccountType.TENANT_WALLET, Objects.requireNonNull(tenantId, "tenantId"));
	}

	public static AccountRef landlordRevenue(UUID landlordId) {
		return new AccountRef(AccountType.LANDLORD_REVENUE, Objects.requireNonNull(landlordId, "landlordId"));
	}

	public static AccountRef gatewayClearing() {
		return new AccountRef(AccountType.GATEWAY_CLEARING, null);
	}

	public static AccountRef vatPayable() {
		return new AccountRef(AccountType.VAT_PAYABLE, null);
	}

	public static AccountRef platformFeeRevenue() {
		return new AccountRef(AccountType.PLATFORM_FEE_REVENUE, null);
	}

}
