package com.theodomart.energy.payment.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.outbox.service.OutboxWriter;
import com.theodomart.energy.payment.config.ReconciliationProperties;
import com.theodomart.energy.payment.domain.GatewayProvider;
import com.theodomart.energy.payment.domain.PaymentIntent;
import com.theodomart.energy.payment.domain.PaymentStatus;
import com.theodomart.energy.payment.repository.PaymentIntentRepository;
import com.theodomart.energy.payment.service.PaymentGateway;
import com.theodomart.energy.payment.service.PaymentGateway.Outcome;
import com.theodomart.energy.payment.service.PaymentGateway.Verification;
import com.theodomart.energy.payment.service.PaymentGatewayException;
import com.theodomart.energy.payment.serviceImpl.TopUpReconciliationJob.Run;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class TopUpReconciliationJobTest {

	private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

	private static final Instant YOUNG = NOW.minus(Duration.ofMinutes(30));

	private static final Instant OLD = NOW.minus(Duration.ofMinutes(61));

	private final PaymentIntentRepository intents = mock();

	private final PaymentGateway gateway = mock();

	private final LedgerService ledger = mock();

	private final OutboxWriter outbox = mock();

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	private final TopUpReconciliationJob job = new TopUpReconciliationJob(intents, gateway,
			new TopUpSettlement(intents, ledger, outbox, clock),
			new ReconciliationProperties(Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(60), 50),
			mock(JdbcTemplate.class), mock(PlatformTransactionManager.class), clock);

	private final Map<UUID, PaymentIntent> stored = new HashMap<>();

	private final List<UUID> due = new ArrayList<>();

	@BeforeEach
	void aDatabaseOfIntents() {
		when(intents.findIdsByStatusCreatedBefore(any(), any(), any())).thenReturn(due);
		when(intents.findByIdForUpdate(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
		when(intents.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
	}

	@Test
	void aPendingTopUpPaidAtPaystackIsCredited() {
		PaymentIntent intent = pending(YOUNG);
		paystackSays(intent, Outcome.SUCCESS);

		Run run = job.reconcile();

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(run).isEqualTo(new Run(true, 1, 1, 0, 0, 0));
		verify(ledger).post(TopUpSettlement.topUpEntry(intent));
	}

	@Test
	void aPendingTopUpStillUnpaidWaitsUntilItIsAnHourOld() {
		PaymentIntent young = pending(YOUNG);
		PaymentIntent old = pending(OLD);
		paystackSays(young, Outcome.PENDING);
		paystackSays(old, Outcome.PENDING);

		Run run = job.reconcile();

		assertThat(young.getStatus()).isEqualTo(PaymentStatus.PAYMENT_PENDING);
		assertThat(old.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(old.getFailureReason()).isEqualTo("Expired unpaid after 60 minutes");
		assertThat(run).isEqualTo(new Run(true, 2, 0, 1, 1, 0));
	}

	@Test
	void anOldTopUpPaidAtPaystackIsCreditedNotExpired() {
		PaymentIntent intent = pending(OLD);
		paystackSays(intent, Outcome.SUCCESS);

		job.reconcile();

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PAID);
	}

	@Test
	void aPaymentPaystackReportsFailedFailsTheIntent() {
		PaymentIntent intent = pending(YOUNG);
		paystackSays(intent, Outcome.FAILED);

		job.reconcile();

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(intent.getFailureReason()).startsWith("Gateway reported the payment failed");
	}

	@Test
	void anIntentThatNeverShowedAPaymentPageFailsOnceItIsStale() {
		PaymentIntent unfinished = initiated();
		PaymentIntent unknown = initiated();
		paystackSays(unfinished, Outcome.PENDING);
		when(gateway.verify(unknown.getId().toString())).thenThrow(PaymentGatewayException.rejected(404));

		Run run = job.reconcile();

		assertThat(unfinished.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(unknown.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(unknown.getFailureReason()).isEqualTo("Never reached the gateway");
		assertThat(run.failed()).isEqualTo(2);
	}

	@Test
	void aPendingIntentPaystackDoesNotKnowIsLeftForAPerson() {
		PaymentIntent intent = pending(OLD);
		when(gateway.verify(intent.getId().toString())).thenThrow(PaymentGatewayException.unreadable());

		job.reconcile();

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PAYMENT_PENDING);
	}

	@Test
	void aPaystackOutageLeavesEverythingForTheNextRunWithoutExpiringIt() {
		PaymentIntent intent = pending(OLD);
		when(gateway.verify(any())).thenThrow(PaymentGatewayException.rejected(503));

		Run run = job.reconcile();

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PAYMENT_PENDING);
		assertThat(run.unchanged()).isOne();
		verify(intents, never()).findByIdForUpdate(any());
	}

	@Test
	void anIntentSettledByAWebhookMeanwhileIsLeftAlone() {
		PaymentIntent intent = pending(OLD);
		paystackSays(intent, Outcome.PENDING);
		when(intents.findByIdForUpdate(intent.getId())).thenAnswer(call -> {
			intent.markPaid(NOW);
			return Optional.of(intent);
		});

		Run run = job.reconcile();

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(run.paid()).isOne();
	}

	@Test
	void oneBrokenIntentDoesNotStopTheRest() {
		PaymentIntent broken = pending(YOUNG);
		PaymentIntent fine = pending(YOUNG);
		when(gateway.verify(broken.getId().toString())).thenThrow(new IllegalStateException("boom"));
		paystackSays(fine, Outcome.SUCCESS);

		Run run = job.reconcile();

		assertThat(fine.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(run).isEqualTo(new Run(true, 2, 1, 0, 0, 1));
	}

	@Test
	void onlyIntentsOlderThanTheStaleCutoffAreAskedFor() {
		job.reconcile();

		verify(intents).findIdsByStatusCreatedBefore(
				java.util.EnumSet.of(PaymentStatus.INITIATED, PaymentStatus.PAYMENT_PENDING),
				NOW.minus(Duration.ofMinutes(10)), org.springframework.data.domain.Limit.of(50));
	}

	private PaymentIntent pending(Instant createdAt) {
		PaymentIntent intent = PaymentIntent.initiate(UUID.randomUUID(), Money.ofKobo(500_000),
				"k-" + UUID.randomUUID(), GatewayProvider.PAYSTACK, createdAt);
		intent.markPending("https://checkout.paystack.com/x", intent.getId().toString(), createdAt);
		return store(intent);
	}

	private PaymentIntent initiated() {
		return store(PaymentIntent.initiate(UUID.randomUUID(), Money.ofKobo(500_000), "k-" + UUID.randomUUID(),
				GatewayProvider.PAYSTACK, OLD));
	}

	private PaymentIntent store(PaymentIntent intent) {
		stored.put(intent.getId(), intent);
		due.add(intent.getId());
		return intent;
	}

	private void paystackSays(PaymentIntent intent, Outcome outcome) {
		when(gateway.verify(intent.getId().toString()))
			.thenReturn(new Verification(outcome, intent.getId().toString(), 500_000, "NGN",
					outcome == Outcome.SUCCESS ? NOW : null, outcome == Outcome.FAILED ? "Declined" : "Abandoned"));
	}

}
