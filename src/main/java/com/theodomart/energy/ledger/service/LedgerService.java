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
	 * <p>Idempotent: posting the same command again, with the same key, returns the entry already posted and changes
	 * nothing. Safe for concurrent retries, and safe inside a caller's transaction, which a repeat leaves usable.
	 *
	 * @throws com.theodomart.energy.common.error.IdempotencyConflictException if the key was used for a different
	 * money movement (type, reference or lines)
	 */
	JournalEntryView post(JournalEntryCommand command);

	// Opening an account must happen in the same transaction as creating its user, so a user can never exist without
	// the account the ledger needs. Both methods refuse to run outside a transaction, and do nothing if it exists.

	/** @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active */
	void openTenantWallet(UUID tenantId);

	/** @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active */
	void openLandlordRevenueAccount(UUID landlordId);

}
