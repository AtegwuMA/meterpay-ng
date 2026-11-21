package com.theodomart.energy.outbox.repository;

import com.theodomart.energy.outbox.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** No delete: a published event is the record that it was sent. Clearing old rows is a later, separate job. */
public interface OutboxEventRepository extends Repository<OutboxEvent, UUID> {

	OutboxEvent save(OutboxEvent event);

	/**
	 * The oldest unpublished events, locked for this transaction. SKIP LOCKED lets several relay instances work at
	 * once without waiting on each other or taking the same row.
	 */
	@Query(value = """
			SELECT * FROM outbox_events
			WHERE published_at IS NULL
			ORDER BY created_at, id
			LIMIT :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<OutboxEvent> lockOldestUnpublished(@Param("limit") int limit);

}
