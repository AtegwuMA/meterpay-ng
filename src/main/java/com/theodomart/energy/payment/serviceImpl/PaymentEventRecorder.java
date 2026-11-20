package com.theodomart.energy.payment.serviceImpl;

import com.theodomart.energy.common.web.CorrelationIdFilter;
import com.theodomart.energy.payment.domain.PaymentEvent;
import com.theodomart.energy.payment.domain.PaymentEventType;
import com.theodomart.energy.payment.repository.PaymentEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the payment log (M4-07). Each row commits on its own, so a record of what Paystack said survives even when
 * the work that followed rolled back: that is exactly the case support needs to see.
 */
@Component
public class PaymentEventRecorder {

	private static final Logger log = LoggerFactory.getLogger(PaymentEventRecorder.class);

	private final PaymentEventRepository events;

	private final TransactionTemplate ownTransaction;

	private final Clock clock;

	public PaymentEventRecorder(PaymentEventRepository events, PlatformTransactionManager transactionManager,
			Clock clock) {
		this.events = events;
		this.ownTransaction = new TransactionTemplate(transactionManager);
		this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
	}

	/**
	 * Never throws: losing one log row is better than failing a payment because the log could not be written. The
	 * failure is logged instead, with the intent, so the gap can be found.
	 *
	 * @param intentId null when the exchange matches none of our intents
	 */
	void record(UUID intentId, PaymentEventType type, ObjectNode payload) {
		String correlationId = correlationId();
		try {
			ownTransaction.executeWithoutResult(status -> events
				.save(new PaymentEvent(intentId, type, payload.toString(), correlationId, clock.instant())));
		}
		catch (DataAccessException ex) {
			log.warn("Payment event not recorded: intentId={} type={} correlationId={} error={}", intentId, type,
					correlationId, ex.getClass().getSimpleName());
		}
	}

	/** The request's ID, or the job run's. Something always: the column is NOT NULL, and a log row needs a thread. */
	private static String correlationId() {
		String current = MDC.get(CorrelationIdFilter.MDC_KEY);
		return current == null || current.isBlank() ? "none-" + UUID.randomUUID() : current;
	}

}
