package com.theodomart.energy.ledger.domain;

/** The accounts in DESIGN 6.3. Each type belongs to exactly one kind of owner, as the database also checks. */
public enum AccountType {

	TENANT_WALLET(OwnerType.TENANT),

	LANDLORD_REVENUE(OwnerType.LANDLORD),

	GATEWAY_CLEARING(OwnerType.PLATFORM),

	VAT_PAYABLE(OwnerType.PLATFORM),

	PLATFORM_FEE_REVENUE(OwnerType.PLATFORM);

	private final OwnerType ownerType;

	AccountType(OwnerType ownerType) {
		this.ownerType = ownerType;
	}

	public OwnerType ownerType() {
		return ownerType;
	}

}
