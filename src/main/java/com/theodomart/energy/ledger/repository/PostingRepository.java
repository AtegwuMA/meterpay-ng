package com.theodomart.energy.ledger.repository;

import com.theodomart.energy.ledger.domain.Posting;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Insert only: no update or delete methods (DESIGN 5.2 rule 4), and the database refuses both anyway (V12). */
public interface PostingRepository extends Repository<Posting, UUID> {

	List<Posting> saveAll(Iterable<Posting> postings);

	List<Posting> findByJournalEntryId(UUID journalEntryId);

}
