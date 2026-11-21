package com.theodomart.energy.outbox.serviceImpl;

import com.theodomart.energy.common.web.CorrelationIdFilter;
import com.theodomart.energy.outbox.config.OutboxProperties;
import com.theodomart.energy.outbox.domain.OutboxEvent;
import com.theodomart.energy.outbox.repository.OutboxEventRepository;
import com.theodomart.energy.outbox.service.EventTopics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes committed outbox events to Kafka (DESIGN 8.4). At least once: if Kafka acknowledges a send but the
 * commit that marks it published fails, the next poll sends it again, so consumers deduplicate on {@code eventId}.
 * <p>
 * Each poll holds its rows locked while it sends, which is what lets SKIP LOCKED keep two instances off the same
 * rows. The batch size and send timeout bound how long that is. Order is kept within one instance's batch, and a
 * failed send stops the batch so later events do not overtake it; across instances two batches can interleave, so
 * consumers must not rely on order between events of one aggregate.
 */
@Component
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboxEventRepository events;

	private final KafkaTemplate<String, String> kafka;

	private final ObjectMapper json;

	private final OutboxProperties properties;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public OutboxRelay(OutboxEventRepository events, KafkaTemplate<String, String> kafka, ObjectMapper json,
			OutboxProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
		this.events = events;
		this.kafka = kafka;
		this.json = json;
		this.properties = properties;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "${meterpay.outbox.poll-interval}",
			fixedDelayString = "${meterpay.outbox.poll-interval}")
	public void poll() {
		publishBatch();
	}

	/** Returns how many events this poll published. */
	public int publishBatch() {
		Integer published = transactions.execute(status -> {
			List<OutboxEvent> batch = events.lockOldestUnpublished(properties.batchSize());
			int sent = 0;
			for (OutboxEvent event : batch) {
				if (!publish(event)) {
					break;
				}
				sent++;
			}
			return sent;
		});
		return published == null ? 0 : published;
	}

	private boolean publish(OutboxEvent event) {
		// The event's own ID, so the relay's log lines join the request that caused the event.
		MDC.put(CorrelationIdFilter.MDC_KEY, event.getCorrelationId());
		try {
			kafka.send(record(event)).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
			event.markPublished(clock.instant());
			log.debug("Outbox event published: eventId={} eventType={} aggregateId={}", event.getId(),
					event.getEventType(), event.getAggregateId());
			return true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			event.recordFailedAttempt();
			return false;
		}
		catch (ExecutionException | TimeoutException | KafkaException ex) {
			event.recordFailedAttempt();
			log.warn("Outbox event not published, will retry: eventId={} eventType={} attempts={} error={}",
					event.getId(), event.getEventType(), event.getAttempts(), ex.getClass().getSimpleName());
			return false;
		}
		finally {
			MDC.remove(CorrelationIdFilter.MDC_KEY);
		}
	}

	/**
	 * The message is an envelope around the payload, so every consumer finds the event id (for deduplication),
	 * the type and the time in the same place whatever the event.
	 */
	ProducerRecord<String, String> record(OutboxEvent event) {
		String envelope = json.createObjectNode()
			.put("eventId", event.getId().toString())
			.put("eventType", event.getEventType())
			.put("aggregateType", event.getAggregateType())
			.put("aggregateId", event.getAggregateId().toString())
			.put("occurredAt", event.getCreatedAt().toString())
			.set("payload", json.readTree(event.getPayload()))
			.toString();
		ProducerRecord<String, String> record = new ProducerRecord<>(EventTopics.ENERGY_EVENTS,
				event.getAggregateId().toString(), envelope);
		record.headers()
			.add(CorrelationIdFilter.HEADER, event.getCorrelationId().getBytes(StandardCharsets.UTF_8))
			.add(EventTopics.EVENT_TYPE_HEADER, event.getEventType().getBytes(StandardCharsets.UTF_8));
		return record;
	}

}
