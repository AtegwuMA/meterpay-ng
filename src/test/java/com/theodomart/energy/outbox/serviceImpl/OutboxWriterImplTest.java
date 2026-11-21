package com.theodomart.energy.outbox.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.theodomart.energy.common.web.CorrelationIdFilter;
import com.theodomart.energy.outbox.domain.OutboxEvent;
import com.theodomart.energy.outbox.repository.OutboxEventRepository;
import com.theodomart.energy.payment.event.WalletToppedUp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OutboxWriterImplTest {

	private static final Instant NOW = Instant.parse("2026-11-21T09:00:00Z");

	private final OutboxEventRepository events = mock();

	private final JsonMapper json = JsonMapper.builder().build();

	private final OutboxWriterImpl writer = new OutboxWriterImpl(events, json, Clock.fixed(NOW, ZoneOffset.UTC));

	private final UUID intent = UUID.randomUUID();

	private final UUID tenant = UUID.randomUUID();

	@AfterEach
	void clearTheMdc() {
		MDC.clear();
	}

	@Test
	void anEventIsStoredWithItsAddressAndTheRecordAsPayload() {
		writer.write(new WalletToppedUp(intent, tenant, 500_000, NOW));

		OutboxEvent saved = saved();
		assertThat(saved.getAggregateType()).isEqualTo("PAYMENT_INTENT");
		assertThat(saved.getAggregateId()).isEqualTo(intent);
		assertThat(saved.getEventType()).isEqualTo("WalletToppedUp");
		assertThat(saved.getCreatedAt()).isEqualTo(NOW);
		assertThat(saved.isPublished()).isFalse();
		JsonNode payload = json.readTree(saved.getPayload());
		assertThat(payload.get("intentId").asString()).isEqualTo(intent.toString());
		assertThat(payload.get("tenantId").asString()).isEqualTo(tenant.toString());
		assertThat(payload.get("amountKobo").asLong()).isEqualTo(500_000);
		assertThat(payload.get("paidAt").asString()).isEqualTo("2026-11-21T09:00:00Z");
	}

	@Test
	void theRequestsCorrelationIdTravelsWithTheEvent() {
		MDC.put(CorrelationIdFilter.MDC_KEY, "req-42");

		writer.write(new WalletToppedUp(intent, tenant, 100, NOW));

		assertThat(saved().getCorrelationId()).isEqualTo("req-42");
	}

	@Test
	void withoutACorrelationIdOneIsGenerated() {
		writer.write(new WalletToppedUp(intent, tenant, 100, NOW));

		assertThat(UUID.fromString(saved().getCorrelationId())).isNotNull();
	}

	@Test
	void anUnsafeCorrelationIdIsReplacedRatherThanCopiedOn() {
		MDC.put(CorrelationIdFilter.MDC_KEY, "x".repeat(65));

		writer.write(new WalletToppedUp(intent, tenant, 100, NOW));

		assertThat(saved().getCorrelationId()).hasSize(36);
	}

	private OutboxEvent saved() {
		ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(events).save(event.capture());
		return event.getValue();
	}

}
