package com.theodomart.energy.ledger.service;

import java.time.Instant;
import java.util.UUID;

public record JournalEntryView(UUID id, EntryType type, String idempotencyKey, String referenceType, UUID referenceId,
		Instant postedAt) {

}
