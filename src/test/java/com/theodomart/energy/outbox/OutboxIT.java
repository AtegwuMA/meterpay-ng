package com.theodomart.energy.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.common.web.CorrelationIdFilter;
import com.theodomart.energy.outbox.service.EventTopics;
import com.theodomart.energy.outbox.service.OutboxWriter;
import com.theodomart.energy.outbox.serviceImpl.OutboxRelay;
import com.theodomart.energy.payment.event.WalletToppedUp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The outbox against real Postgres and Kafka. The scheduled relay runs too; the tests also call it directly. */
class OutboxIT extends IntegrationTest {

	private static final Duration WAIT = Duration.ofSeconds(10);

	private final OutboxWriter writer;

	private final OutboxRelay relay;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	private KafkaConsumer<String, String> consumer;

	@Autowired
	OutboxIT(OutboxWriter writer, OutboxRelay relay, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.writer = writer;
		this.relay = relay;
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void aConsumerReadingFromTheStart() {
		consumer = new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
				ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(),
				new StringDeserializer());
		consumer.subscribe(List.of(EventTopics.ENERGY_EVENTS));
	}

	@AfterEach
	void closeTheConsumer() {
		consumer.close();
		MDC.clear();
	}

	@Test
	void aCommittedEventReachesKafkaWithItsCorrelationId() {
		UUID intent = UUID.randomUUID();
		MDC.put(CorrelationIdFilter.MDC_KEY, "outbox-it-42");
		transactions.executeWithoutResult(status -> writer.write(event(intent)));
		MDC.clear();

		relay.publishBatch();

		ConsumerRecord<String, String> record = awaitOne(intent);
		assertThat(record.key()).isEqualTo(intent.toString());
		assertThat(header(record, CorrelationIdFilter.HEADER)).isEqualTo("outbox-it-42");
		assertThat(header(record, EventTopics.EVENT_TYPE_HEADER)).isEqualTo("WalletToppedUp");
		assertThat(record.value()).contains("\"eventType\":\"WalletToppedUp\"").contains("\"amountKobo\":500000");
		assertThat(jdbc.queryForObject(
				"SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?", Boolean.class, intent))
			.isTrue();
	}

	@Test
	void eventIsNotPublishedWhenTheBusinessTransactionRollsBack() {
		UUID rolledBack = UUID.randomUUID();
		UUID committed = UUID.randomUUID();

		transactions.executeWithoutResult(status -> {
			writer.write(event(rolledBack));
			status.setRollbackOnly();
		});
		// Written after, so once it arrives the relay has certainly passed the point where the other would be.
		transactions.executeWithoutResult(status -> writer.write(event(committed)));
		relay.publishBatch();

		List<ConsumerRecord<String, String>> seen = awaitUntil(committed);
		assertThat(seen).extracting(ConsumerRecord::key).doesNotContain(rolledBack.toString());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class,
				rolledBack))
			.isZero();
	}

	@Test
	void anEventCannotBeWrittenOutsideATransaction() {
		assertThatThrownBy(() -> writer.write(event(UUID.randomUUID())))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void relaysRunningTogetherPublishEachEventOnce() throws Exception {
		Set<String> mine = new HashSet<>();
		transactions.executeWithoutResult(status -> {
			for (int i = 0; i < 30; i++) {
				UUID intent = UUID.randomUUID();
				mine.add(intent.toString());
				writer.write(event(intent));
			}
		});

		int relays = 4;
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(relays)) {
			List<Future<Integer>> runs = new ArrayList<>();
			for (int i = 0; i < relays; i++) {
				runs.add(pool.submit(() -> {
					start.await();
					return relay.publishBatch();
				}));
			}
			start.countDown();
			for (Future<Integer> run : runs) {
				run.get();
			}
		}
		// Anything a relay left (the scheduled one may also have taken some) goes out now.
		relay.publishBatch();

		List<String> received = new ArrayList<>();
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (System.nanoTime() < deadline && received.size() < mine.size()) {
			consumer.poll(Duration.ofMillis(500)).forEach(record -> {
				if (mine.contains(record.key())) {
					received.add(record.key());
				}
			});
		}
		// A little longer, so a duplicate would have time to show up.
		consumer.poll(Duration.ofSeconds(1)).forEach(record -> {
			if (mine.contains(record.key())) {
				received.add(record.key());
			}
		});
		assertThat(received).hasSize(mine.size()).doesNotHaveDuplicates();
	}

	private ConsumerRecord<String, String> awaitOne(UUID aggregate) {
		List<ConsumerRecord<String, String>> seen = awaitUntil(aggregate);
		return seen.getLast();
	}

	/** Every record read until one for the aggregate arrives; the last one is it. */
	private List<ConsumerRecord<String, String>> awaitUntil(UUID aggregate) {
		List<ConsumerRecord<String, String>> seen = new ArrayList<>();
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (System.nanoTime() < deadline) {
			for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
				seen.add(record);
				if (record.key().equals(aggregate.toString())) {
					return seen;
				}
			}
		}
		throw new AssertionError("No event for " + aggregate + " within " + WAIT);
	}

	private static String header(ConsumerRecord<String, String> record, String name) {
		return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
	}

	private static WalletToppedUp event(UUID intent) {
		return new WalletToppedUp(intent, UUID.randomUUID(), 500_000, Instant.parse("2026-11-21T09:00:00Z"));
	}

}
