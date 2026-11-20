package com.theodomart.energy.payment.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.theodomart.energy.common.web.CorrelationIdFilter;
import com.theodomart.energy.payment.domain.PaymentEvent;
import com.theodomart.energy.payment.domain.PaymentEventType;
import com.theodomart.energy.payment.repository.PaymentEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class PaymentEventRecorderTest {

	private static final Instant NOW = Instant.parse("2026-11-20T09:00:00.123456789Z");

	private final PaymentEventRepository events = mock();

	private final PaymentEventRecorder recorder = new PaymentEventRecorder(events,
			mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));

	private final JsonMapper json = JsonMapper.builder().build();

	@AfterEach
	void clearTheMdc() {
		MDC.clear();
	}

	@Test
	void anEventCarriesTheRequestsCorrelationId() {
		MDC.put(CorrelationIdFilter.MDC_KEY, "req-42");
		UUID intent = UUID.randomUUID();

		recorder.record(intent, PaymentEventType.VERIFY_SUCCEEDED, json.createObjectNode().put("outcome", "PENDING"));

		PaymentEvent saved = saved();
		assertThat(saved.getIntentId()).isEqualTo(intent);
		assertThat(saved.getEventType()).isEqualTo(PaymentEventType.VERIFY_SUCCEEDED);
		assertThat(saved.getCorrelationId()).isEqualTo("req-42");
		assertThat(saved.getPayload()).isEqualTo("{\"outcome\":\"PENDING\"}");
		// Microseconds, as Postgres stores them.
		assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-11-20T09:00:00.123456Z"));
	}

	@Test
	void withoutACorrelationIdOneIsMadeUp() {
		recorder.record(null, PaymentEventType.WEBHOOK_RECEIVED, json.createObjectNode());

		assertThat(saved().getCorrelationId()).startsWith("none-");
	}

	@Test
	void aLogThatCannotBeWrittenNeverFailsThePayment() {
		when(events.save(any())).thenThrow(new DataAccessResourceFailureException("down"));

		assertThatNoException().isThrownBy(
				() -> recorder.record(null, PaymentEventType.VERIFY_FAILED, json.createObjectNode()));
	}

	private PaymentEvent saved() {
		ArgumentCaptor<PaymentEvent> event = ArgumentCaptor.forClass(PaymentEvent.class);
		verify(events).save(event.capture());
		return event.getValue();
	}

}
