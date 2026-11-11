package com.theodomart.energy.ledger.service;

/**
 * The accounts in DESIGN 6.3. Each belongs to exactly one kind of owner, as the database also checks, and has a
 * normal balance: the side that makes it grow. Balances are stored positive in that direction.
 */
public enum AccountType {

	/** Money held for the tenant. A credit tops it up, a debit spends it. */
	TENANT_WALLET(OwnerType.TENANT, Direction.CREDIT),

	LANDLORD_REVENUE(OwnerType.LANDLORD, Direction.CREDIT),

	/** Money on its way from the payment gateway. A debit means more is owed to us. */
	GATEWAY_CLEARING(OwnerType.PLATFORM, Direction.DEBIT),

	VAT_PAYABLE(OwnerType.PLATFORM, Direction.CREDIT),

	PLATFORM_FEE_REVENUE(OwnerType.PLATFORM, Direction.CREDIT);

	private final OwnerType ownerType;

	private final Direction normalBalance;

	AccountType(OwnerType ownerType, Direction normalBalance) {
		this.ownerType = ownerType;
		this.normalBalance = normalBalance;
	}

	public OwnerType ownerType() {
		return ownerType;
	}

	public Direction normalBalance() {
		return normalBalance;
	}

}
