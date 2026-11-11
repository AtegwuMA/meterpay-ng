package com.theodomart.energy.ledger.service;

import java.util.UUID;

/**
 * Opening an account must happen in the same transaction as creating its user, so a user can never exist without the
 * account the ledger needs. Both methods refuse to run outside a transaction, and do nothing if the account exists.
 */
public interface LedgerService {

	/** @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active */
	void openTenantWallet(UUID tenantId);

	/** @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active */
	void openLandlordRevenueAccount(UUID landlordId);

}
