package com.theodomart.energy.outbox.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the relay runs (M5-01). The poll interval is also read by @Scheduled from the same prefix.
 *
 * @param batchSize the most events one poll publishes, which bounds how long a poll holds its row locks
 * @param sendTimeout how long one send may wait for Kafka's acknowledgement before the poll gives up
 * @param topicPartitions for {@code energy.events.v1} when the app creates it
 */
@ConfigurationProperties("meterpay.outbox")
public record OutboxProperties(Duration pollInterval, int batchSize, Duration sendTimeout, int topicPartitions) {

	public OutboxProperties {
		requirePositive(pollInterval, "poll-interval");
		requirePositive(sendTimeout, "send-timeout");
		if (batchSize < 1) {
			throw new IllegalStateException("meterpay.outbox.batch-size must be at least 1");
		}
		if (topicPartitions < 1) {
			throw new IllegalStateException("meterpay.outbox.topic-partitions must be at least 1");
		}
	}

	private static void requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException("meterpay.outbox." + name + " must be positive");
		}
	}

}
