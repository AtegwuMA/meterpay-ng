package com.theodomart.energy.ledger.service;

import java.time.Instant;
import java.util.UUID;

/**
 * One journal entry as it touched the wallet. An entry with several lines on the wallet shows once, with its net.
 *
 * @param direction CREDIT when money came into the wallet, DEBIT when it left
 * @param amountKobo always positive: the direction says which way it moved
 */
public record WalletTransactionView(UUID entryId, EntryType type, Direction direction, long amountKobo,
		String referenceType, UUID referenceId, String description, Instant postedAt) {

}
