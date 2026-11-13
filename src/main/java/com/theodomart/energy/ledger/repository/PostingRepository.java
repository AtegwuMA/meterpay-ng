package com.theodomart.energy.ledger.repository;

import com.theodomart.energy.ledger.domain.Posting;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Insert only: no update or delete methods (DESIGN 5.2 rule 4), and the database refuses both anyway (V12). */
public interface PostingRepository extends Repository<Posting, UUID> {

	List<Posting> saveAll(Iterable<Posting> postings);

	List<Posting> findByJournalEntryId(UUID journalEntryId);

	/**
	 * An account's history, one row per entry with the entry's net effect, newest first. The sort ends in the entry id
	 * so entries posted in the same microsecond keep a stable order across pages (DESIGN 9).
	 */
	@Query(value = """
			SELECT new com.theodomart.energy.ledger.repository.WalletLine(e.id, e.entryType, e.referenceType,
				e.referenceId, e.description, e.postedAt,
				SUM(CASE WHEN p.direction = com.theodomart.energy.ledger.service.Direction.CREDIT
					THEN p.amountKobo ELSE -p.amountKobo END))
			FROM Posting p JOIN JournalEntry e ON e.id = p.journalEntryId
			WHERE p.accountId = :accountId
			GROUP BY e.id, e.entryType, e.referenceType, e.referenceId, e.description, e.postedAt
			ORDER BY e.postedAt DESC, e.id DESC
			""", countQuery = """
			SELECT COUNT(DISTINCT p.journalEntryId) FROM Posting p WHERE p.accountId = :accountId
			""")
	Page<WalletLine> findHistory(@Param("accountId") UUID accountId, Pageable pageable);

}
