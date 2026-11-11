package com.theodomart.energy.ledger.service;

import java.util.List;
import java.util.UUID;

/**
 * Everything needed to post one entry. Whether the lines balance is checked when it is posted, in one place.
 *
 * @param idempotencyKey unique for this money movement, so a retry cannot post it twice (M3-04)
 * @param referenceType what the entry is about, such as {@code PAYMENT_INTENT}; null together with referenceId
 * @param description optional, for people reading the ledger
 */
public record JournalEntryCommand(EntryType type, String idempotencyKey, String referenceType, UUID referenceId,
		String description, List<PostingLine> lines) {

	public JournalEntryCommand {
		lines = lines == null ? List.of() : List.copyOf(lines);
	}

}
