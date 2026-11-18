package com.theodomart.energy.payment.repository;

import com.theodomart.energy.payment.domain.PaymentIntent;
import com.theodomart.energy.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** No delete: an intent is a record of a payment attempt, kept whatever became of it. */
public interface PaymentIntentRepository extends Repository<PaymentIntent, UUID> {

	PaymentIntent saveAndFlush(PaymentIntent intent);

	Optional<PaymentIntent> findById(UUID id);

	Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);

	/**
	 * Locks the intent for the rest of the transaction, so two confirmations of one payment (a webhook delivered
	 * twice, or a webhook and the reconciliation job) settle one after the other and the second sees PAID.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT i FROM PaymentIntent i WHERE i.id = :id")
	Optional<PaymentIntent> findByIdForUpdate(@Param("id") UUID id);

	/** Intents not yet settled and created before the cutoff, oldest first (index from V14). */
	@Query("""
			SELECT i.id FROM PaymentIntent i
			WHERE i.status IN :statuses AND i.createdAt < :before
			ORDER BY i.createdAt, i.id
			""")
	List<UUID> findIdsByStatusCreatedBefore(@Param("statuses") Collection<PaymentStatus> statuses,
			@Param("before") Instant before, Limit limit);

}
