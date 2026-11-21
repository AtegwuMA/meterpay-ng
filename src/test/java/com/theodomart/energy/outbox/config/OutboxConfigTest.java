package com.theodomart.energy.outbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.common.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The interceptor consumers rely on, the topic definition and the relay's settings. */
class OutboxConfigTest {

	private static final Duration SECOND = Duration.ofSeconds(1);

	private final CorrelationIdRecordInterceptor interceptor = new CorrelationIdRecordInterceptor();

	@AfterEach
	void clearTheMdc() {
		MDC.clear();
	}

	@Test
	void aConsumerSeesTheMessagesCorrelationIdUntilItIsDone() {
		ConsumerRecord<Object, Object> record = record("req-42");

		assertThat(interceptor.intercept(record, null)).isSameAs(record);
		assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("req-42");

		interceptor.afterRecord(record, null);
		assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void aMissingOrUnsafeHeaderGetsAFreshId() {
		interceptor.intercept(record(null), null);
		String generated = MDC.get(CorrelationIdFilter.MDC_KEY);
		interceptor.intercept(record("bad\nlog line"), null);

		assertThat(generated).hasSize(36);
		assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).hasSize(36).doesNotContain("\n");
	}

	@Test
	void theTopicHasTheConfiguredPartitions() {
		OutboxConfig config = new OutboxConfig();

		assertThat(config.energyEventsTopic(new OutboxProperties(SECOND, 100, SECOND, 3)).numPartitions())
			.isEqualTo(3);
		assertThat(config.correlationIdRecordInterceptor()).isInstanceOf(CorrelationIdRecordInterceptor.class);
	}

	@Test
	void theRelaysSettingsMustMakeSense() {
		assertThatThrownBy(() -> new OutboxProperties(Duration.ZERO, 100, SECOND, 3))
			.hasMessageContaining("poll-interval");
		assertThatThrownBy(() -> new OutboxProperties(SECOND, 100, null, 3)).hasMessageContaining("send-timeout");
		assertThatThrownBy(() -> new OutboxProperties(SECOND, 0, SECOND, 3)).hasMessageContaining("batch-size");
		assertThatThrownBy(() -> new OutboxProperties(SECOND, 100, SECOND, 0))
			.hasMessageContaining("topic-partitions");
	}

	private static ConsumerRecord<Object, Object> record(String correlationId) {
		ConsumerRecord<Object, Object> record = new ConsumerRecord<>("energy.events.v1", 0, 0, "key", "value");
		if (correlationId != null) {
			record.headers().add(CorrelationIdFilter.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
		}
		return record;
	}

}
