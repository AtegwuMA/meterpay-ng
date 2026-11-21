package com.theodomart.energy.outbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

	private static final Instant NOW = Instant.parse("2026-11-21T09:00:00.123456789Z");

	@Test
	void aNewEventIsUnpublishedAndKeptToTheMicrosecond() {
		OutboxEvent event = event();

		assertThat(event.isPublished()).isFalse();
		assertThat(event.getAttempts()).isZero();
		assertThat(event.getCreatedAt()).isEqualTo(Instant.parse("2026-11-21T09:00:00.123456Z"));
	}

	@Test
	void failedAttemptsAreCountedAndTheSuccessfulOneToo() {
		OutboxEvent event = event();

		event.recordFailedAttempt();
		event.markPublished(NOW.plusSeconds(5));

		assertThat(event.getAttempts()).isEqualTo(2);
		assertThat(event.isPublished()).isTrue();
	}

	@Test
	void anEventIsPublishedOnce() {
		OutboxEvent event = event();
		event.markPublished(NOW);

		assertThatThrownBy(() -> event.markPublished(NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void anEventNeedsAnAddressATypeAndACorrelationId() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> new OutboxEvent(" ", id, "T", "{}", "c", NOW))
			.hasMessageContaining("aggregateType");
		assertThatThrownBy(() -> new OutboxEvent("A", null, "T", "{}", "c", NOW))
			.hasMessageContaining("aggregateId");
		assertThatThrownBy(() -> new OutboxEvent("A", id, "", "{}", "c", NOW)).hasMessageContaining("eventType");
		assertThatThrownBy(() -> new OutboxEvent("A", id, "T", "{}", null, NOW))
			.hasMessageContaining("correlationId");
	}

	@Test
	void eventsAreTheSameOnlyWhenTheirIdsAre() {
		OutboxEvent event = event();

		assertThat(event).isEqualTo(event).isNotEqualTo(event()).hasSameHashCodeAs(event);
		assertThat(event.toString()).contains("WalletToppedUp");
	}

	private static OutboxEvent event() {
		return new OutboxEvent("PAYMENT_INTENT", UUID.randomUUID(), "WalletToppedUp", "{}", "req-1", NOW);
	}

}
