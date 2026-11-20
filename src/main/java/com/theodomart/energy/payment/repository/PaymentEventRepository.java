package com.theodomart.energy.payment.repository;

import com.theodomart.energy.payment.domain.PaymentEvent;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Insert only: no update or delete methods, and the database refuses both anyway (V13). */
public interface PaymentEventRepository extends Repository<PaymentEvent, UUID> {

	PaymentEvent save(PaymentEvent event);

}
