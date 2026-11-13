package com.theodomart.energy.ledger.repository;

import com.theodomart.energy.ledger.service.EntryType;
import java.time.Instant;
import java.util.UUID;

/** One entry's net effect on one account: positive when it credited the account, negative when it debited it. */
public record WalletLine(UUID entryId, EntryType type, String referenceType, UUID referenceId, String description,
		Instant postedAt, Long netCreditKobo) {

}
