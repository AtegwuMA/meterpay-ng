package com.theodomart.energy.outbox.serviceImpl;

import com.theodomart.energy.common.web.CorrelationIdFilter;
import com.theodomart.energy.outbox.domain.OutboxEvent;
import com.theodomart.energy.outbox.repository.OutboxEventRepository;
import com.theodomart.energy.outbox.service.DomainEvent;
import com.theodomart.energy.outbox.service.OutboxWriter;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxWriterImpl implements OutboxWriter {

	private final OutboxEventRepository events;

	private final ObjectMapper json;

	private final Clock clock;

	public OutboxWriterImpl(OutboxEventRepository events, ObjectMapper json, Clock clock) {
		this.events = events;
		this.json = json;
		this.clock = clock;
	}

	// MANDATORY: the whole point is to commit with the change. Outside a transaction there is no change to join.
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void write(DomainEvent event) {
		Objects.requireNonNull(event, "event");
		events.save(new OutboxEvent(event.aggregateType(), event.aggregateId(), event.eventType(),
				json.writeValueAsString(event), CorrelationIdFilter.safeOrNew(MDC.get(CorrelationIdFilter.MDC_KEY)),
				clock.instant()));
	}

}
