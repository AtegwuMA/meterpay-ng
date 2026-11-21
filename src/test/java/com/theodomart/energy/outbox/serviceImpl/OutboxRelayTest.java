package com.theodomart.energy.outbox.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.theodomart.energy.outbox.config.OutboxProperties;
import com.theodomart.energy.outbox.domain.OutboxEvent;
import com.theodomart.energy.outbox.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OutboxRelayTest {

	private static final Instant CREATED = Instant.parse("2026-11-21T09:00:00Z");

	private static final Instant NOW = CREATED.plusSeconds(1);

	private final OutboxEventRepository events = mock();

	@SuppressWarnings("unchecked")
	private final KafkaTemplate<String, String> kafka = mock();

	private final JsonMapper json = JsonMapper.builder().build();

	private final OutboxRelay relay = new OutboxRelay(events, kafka, json,
			new OutboxProperties(Duration.ofSeconds(1), 10, Duration.ofMillis(200), 3),
			mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void eachEventIsSentThenMarkedPublished() {
		OutboxEvent first = event("req-1");
		OutboxEvent second = event("req-2");
		when(events.lockOldestUnpublished(10)).thenReturn(List.of(first, second));
		when(kafka.send(any(ProducerRecord.class))).thenReturn(acknowledged());

		assertThat(relay.publishBatch()).isEqualTo(2);

		assertThat(first.getPublishedAt()).isEqualTo(NOW);
		assertThat(second.isPublished()).isTrue();
		assertThat(first.getAttempts()).isOne();
	}

	@Test
	void aFailedSendStopsTheBatchSoNothingOvertakesIt() {
		OutboxEvent first = event("req-1");
		OutboxEvent second = event("req-2");
		when(events.lockOldestUnpublished(anyInt())).thenReturn(List.of(first, second));
		when(kafka.send(any(ProducerRecord.class)))
			.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

		assertThat(relay.publishBatch()).isZero();

		assertThat(first.isPublished()).isFalse();
		assertThat(first.getAttempts()).isOne();
		assertThat(second.getAttempts()).isZero();
		verify(kafka, times(1)).send(any(ProducerRecord.class));
	}

	@Test
	void aSendThatNeverAnswersTimesOut() {
		OutboxEvent event = event("req-1");
		when(events.lockOldestUnpublished(anyInt())).thenReturn(List.of(event));
		when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

		assertThat(relay.publishBatch()).isZero();
		assertThat(event.getAttempts()).isOne();
	}

	@Test
	void nothingToSendIsNotAnError() {
		when(events.lockOldestUnpublished(anyInt())).thenReturn(List.of());

		assertThat(relay.publishBatch()).isZero();
	}

	@Test
	void theMessageIsKeyedByAggregateWithTheCorrelationIdInAHeaderNotThePayload() {
		OutboxEvent event = event("req-77");

		ProducerRecord<String, String> record = relay.record(event);

		assertThat(record.topic()).isEqualTo("energy.events.v1");
		assertThat(record.key()).isEqualTo(event.getAggregateId().toString());
		assertThat(header(record, "X-Correlation-Id")).isEqualTo("req-77");
		assertThat(header(record, "X-Event-Type")).isEqualTo("WalletToppedUp");
		JsonNode envelope = json.readTree(record.value());
		assertThat(envelope.get("eventId").asString()).isEqualTo(event.getId().toString());
		assertThat(envelope.get("eventType").asString()).isEqualTo("WalletToppedUp");
		assertThat(envelope.get("aggregateType").asString()).isEqualTo("PAYMENT_INTENT");
		assertThat(envelope.get("occurredAt").asString()).isEqualTo("2026-11-21T09:00:00Z");
		assertThat(envelope.get("payload").get("amountKobo").asLong()).isEqualTo(500);
		assertThat(record.value()).doesNotContain("req-77");
	}

	private static OutboxEvent event(String correlationId) {
		return new OutboxEvent("PAYMENT_INTENT", UUID.randomUUID(), "WalletToppedUp", "{\"amountKobo\": 500}",
				correlationId, CREATED);
	}

	private static String header(ProducerRecord<String, String> record, String name) {
		return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
	}

	private static CompletableFuture<SendResult<String, String>> acknowledged() {
		return CompletableFuture.completedFuture(null);
	}

}
