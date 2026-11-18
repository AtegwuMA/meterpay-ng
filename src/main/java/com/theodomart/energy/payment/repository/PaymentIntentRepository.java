package com.theodomart.energy.payment.repository;

import com.theodomart.energy.payment.domain.PaymentIntent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** No delete: an intent is a record of a payment attempt, kept whatever became of it. */
public interface PaymentIntentRepository extends Repository<PaymentIntent, UUID> {

	PaymentIntent saveAndFlush(PaymentIntent intent);

	Optional<PaymentIntent> findById(UUID id);

	Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);

}
