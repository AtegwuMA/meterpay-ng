package com.theodomart.energy.ledger.repository;

import com.theodomart.energy.ledger.domain.JournalEntry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Insert only: no update or delete methods (DESIGN 5.2 rule 4), and the database refuses both anyway (V12). */
public interface JournalEntryRepository extends Repository<JournalEntry, UUID> {

	/**
	 * Inserts the entry unless its idempotency key is taken, and returns how many rows it inserted. The UNIQUE
	 * constraint decides, so two concurrent posts with one key cannot both get through. ON CONFLICT rather than
	 * catching the violation: posting joins the caller's transaction, and in Postgres a failed insert would abort it.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO journal_entries (id, entry_type, reference_type, reference_id, idempotency_key, description,
				posted_at, created_at)
			VALUES (:id, :entryType, :referenceType, :referenceId, :idempotencyKey, :description, :postedAt, :createdAt)
			ON CONFLICT (idempotency_key) DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") UUID id, @Param("entryType") String entryType,
			@Param("referenceType") String referenceType, @Param("referenceId") UUID referenceId,
			@Param("idempotencyKey") String idempotencyKey, @Param("description") String description,
			@Param("postedAt") Instant postedAt, @Param("createdAt") Instant createdAt);

	Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);

}
