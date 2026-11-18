package com.theodomart.energy.payment.repository;

import com.theodomart.energy.payment.domain.PaymentIntent;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
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

}
