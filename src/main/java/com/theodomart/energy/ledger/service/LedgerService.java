package com.theodomart.energy.ledger.service;

import java.util.UUID;

public interface LedgerService {

	/**
	 * Posts one balanced entry and updates every balance it touches, all or nothing. Joins the caller's transaction
	 * if there is one, so a purchase and its money movement commit together.
	 *
	 * @throws IllegalArgumentException if the entry is malformed or its debits and credits differ: a bug in the caller
	 * @throws InsufficientWalletBalanceException if a tenant's wallet would go below zero
	 * @throws org.springframework.dao.OptimisticLockingFailureException if an account changed concurrently
	 * @throws org.springframework.dao.DataIntegrityViolationException if the idempotency key was used before (M3-04
	 * will return the existing entry instead)
	 */
	JournalEntryView post(JournalEntryCommand command);

	// Opening an account must happen in the same transaction as creating its user, so a user can never exist without
	// the account the ledger needs. Both methods refuse to run outside a transaction, and do nothing if it exists.

	/** @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active */
	void openTenantWallet(UUID tenantId);

	/** @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active */
	void openLandlordRevenueAccount(UUID landlordId);

}
