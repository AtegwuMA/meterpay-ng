package com.theodomart.energy.ledger.repository;

import com.theodomart.energy.ledger.domain.JournalEntry;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Insert only: no update or delete methods (DESIGN 5.2 rule 4), and the database refuses both anyway (V12). */
public interface JournalEntryRepository extends Repository<JournalEntry, UUID> {

	JournalEntry saveAndFlush(JournalEntry entry);

}
