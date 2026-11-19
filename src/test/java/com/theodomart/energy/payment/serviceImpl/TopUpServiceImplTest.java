package com.theodomart.energy.payment.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.common.domain.Role;
import com.theodomart.energy.common.error.IdempotencyConflictException;
import com.theodomart.energy.common.error.InvalidRequestException;
import com.theodomart.energy.common.error.ResourceNotFoundException;
import com.theodomart.energy.identity.service.IdentityService;
import com.theodomart.energy.identity.service.UserView;
import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.payment.domain.GatewayProvider;
import com.theodomart.energy.payment.domain.PaymentIntent;
import com.theodomart.energy.payment.domain.PaymentStatus;
import com.theodomart.energy.payment.repository.PaymentIntentRepository;
import com.theodomart.energy.payment.service.PaymentGateway;
import com.theodomart.energy.payment.service.PaymentGateway.Initialization;
import com.theodomart.energy.payment.service.PaymentGateway.Outcome;
import com.theodomart.energy.payment.service.PaymentGateway.Verification;
import com.theodomart.energy.payment.service.PaymentGatewayException;
import com.theodomart.energy.payment.service.TopUpService.StartedTopUp;
import com.theodomart.energy.payment.service.TopUpView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

class TopUpServiceImplTest {

	private static final Instant NOW = Instant.parse("2026-11-16T09:00:00Z");

	private static final String URL = "https://checkout.paystack.com/abc";

	private final PaymentIntentRepository intents = mock();

	private final PaymentGateway gateway = mock();

	private final IdentityService identity = mock();

	private final LedgerService ledger = mock();

	private final Map<UUID, PaymentIntent> stored = new HashMap<>();

	private final TopUpServiceImpl topUps = new TopUpServiceImpl(intents, gateway, identity,
			new TopUpSettlement(intents, ledger, Clock.fixed(NOW, ZoneOffset.UTC)), mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));

	private final UUID tenant = UUID.randomUUID();

	@BeforeEach
	void aTenantAndAWorkingDatabase() {
		when(identity.currentUser(tenant))
			.thenReturn(new UserView(tenant, "tolu@example.com", "+2348031234567", "Tolu", Role.TENANT, NOW));
		when(intents.saveAndFlush(any())).thenAnswer(call -> {
			PaymentIntent intent = call.getArgument(0);
			stored.put(intent.getId(), intent);
			return intent;
		});
		when(intents.findById(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
		when(intents.findByIdForUpdate(any()))
			.thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
	}

	@Test
	void aNewTopUpIsCommittedThenSentToPaystackThenMarkedPending() {
		when(gateway.initialize(anyString(), eq(Money.ofKobo(500_000)), eq("tolu@example.com")))
			.thenAnswer(call -> new Initialization(URL, call.getArgument(0)));

		StartedTopUp started = topUps.start(tenant, "key-1", Money.ofKobo(500_000));

		assertThat(started.created()).isTrue();
		assertThat(started.topUp().status()).isEqualTo("PAYMENT_PENDING");
		assertThat(started.topUp().authorizationUrl()).isEqualTo(URL);
		verify(intents, times(2)).saveAndFlush(any());
		// Paystack's reference is our intent id, so every payment traces back to its intent.
		verify(gateway).initialize(eq(started.topUp().intentId().toString()), any(), any());
	}

	@Test
	void theSameKeyAndAmountReturnsTheFirstIntentWithoutCallingPaystack() {
		PaymentIntent first = existing("key-2", 500_000);
		duplicateKey();

		StartedTopUp again = topUps.start(tenant, "key-2", Money.ofKobo(500_000));

		assertThat(again.created()).isFalse();
		assertThat(again.topUp().intentId()).isEqualTo(first.getId());
		verify(gateway, never()).initialize(any(), any(), any());
	}

	@Test
	void theSameKeyForADifferentAmountIsRefused() {
		existing("key-3", 500_000);
		duplicateKey();

		assertThatThrownBy(() -> topUps.start(tenant, "key-3", Money.ofKobo(900_000)))
			.isInstanceOf(IdempotencyConflictException.class);
		verify(gateway, never()).initialize(any(), any(), any());
	}

	@Test
	void aPaystackOutageLeavesTheIntentForReconciliation() {
		when(gateway.initialize(any(), any(), any())).thenThrow(PaymentGatewayException.rejected(503));

		assertThatThrownBy(() -> topUps.start(tenant, "key-4", Money.ofKobo(500_000)))
			.isInstanceOf(PaymentGatewayException.class);

		assertThat(stored.values()).singleElement()
			.satisfies(intent -> assertThat(intent.getStatus()).isEqualTo(PaymentStatus.INITIATED));
	}

	@Test
	void aRefusalFromPaystackFailsTheIntentWithTheReason() {
		when(gateway.initialize(any(), any(), any())).thenThrow(PaymentGatewayException.rejected(400));

		assertThatThrownBy(() -> topUps.start(tenant, "key-5", Money.ofKobo(500_000)))
			.isInstanceOf(PaymentGatewayException.class);

		assertThat(stored.values()).singleElement().satisfies(intent -> {
			assertThat(intent.getStatus()).isEqualTo(PaymentStatus.FAILED);
			assertThat(intent.getFailureReason()).contains("400");
		});
	}

	@Test
	void aBadKeyIsTheClientsMistake() {
		assertThatThrownBy(() -> topUps.start(tenant, " ", Money.ofKobo(500_000)))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("Idempotency-Key");
		verify(intents, never()).saveAndFlush(any());
	}

	@Test
	void aFailedIntentDoesNotOfferItsPaymentPage() {
		PaymentIntent intent = PaymentIntent.initiate(tenant, Money.ofKobo(100), "k", GatewayProvider.PAYSTACK, NOW);
		intent.markPending(URL, "ref", NOW);
		intent.markFailed("Expired", NOW);

		assertThat(TopUpServiceImpl.toView(intent).authorizationUrl()).isNull();
	}

	@Test
	void aPendingTopUpPaidAtPaystackIsSettledWhenTheTenantAsks() {
		PaymentIntent intent = pendingIntent();
		paystackSays(intent, Outcome.SUCCESS);

		TopUpView view = topUps.status(tenant, intent.getId());

		assertThat(view.status()).isEqualTo("PAID");
		verify(ledger).post(TopUpSettlement.topUpEntry(intent));
	}

	@Test
	void aPendingTopUpStillUnpaidStaysPending() {
		PaymentIntent intent = pendingIntent();
		paystackSays(intent, Outcome.PENDING);

		assertThat(topUps.status(tenant, intent.getId()).status()).isEqualTo("PAYMENT_PENDING");
		verify(ledger, never()).post(any());
	}

	@Test
	void aSettledTopUpIsReturnedWithoutAskingPaystack() {
		PaymentIntent intent = pendingIntent();
		intent.markFailed("Expired", NOW);

		assertThat(topUps.status(tenant, intent.getId()).status()).isEqualTo("FAILED");
		verify(gateway, never()).verify(any());
	}

	@Test
	void whenPaystackIsDownTheStoredStatusIsStillAnswered() {
		PaymentIntent intent = pendingIntent();
		when(gateway.verify(any())).thenThrow(PaymentGatewayException.rejected(503));

		TopUpView view = topUps.status(tenant, intent.getId());

		assertThat(view.status()).isEqualTo("PAYMENT_PENDING");
		assertThat(view.authorizationUrl()).isEqualTo(URL);
	}

	@Test
	void aTopUpSettledByAWebhookMeanwhileIsNotSettledAgain() {
		PaymentIntent intent = pendingIntent();
		paystackSays(intent, Outcome.SUCCESS);
		when(intents.findByIdForUpdate(intent.getId())).thenAnswer(call -> {
			intent.markPaid(NOW);
			return Optional.of(intent);
		});

		assertThat(topUps.status(tenant, intent.getId()).status()).isEqualTo("PAID");
		verify(ledger, never()).post(any());
	}

	@Test
	void anotherTenantsTopUpLooksLikeOneThatDoesNotExist() {
		PaymentIntent intent = pendingIntent();

		assertThatThrownBy(() -> topUps.status(UUID.randomUUID(), intent.getId()))
			.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> topUps.status(tenant, UUID.randomUUID()))
			.isInstanceOf(ResourceNotFoundException.class);
		verify(gateway, never()).verify(any());
	}

	private PaymentIntent pendingIntent() {
		PaymentIntent intent = PaymentIntent.initiate(tenant, Money.ofKobo(500_000), "k-" + UUID.randomUUID(),
				GatewayProvider.PAYSTACK, NOW);
		intent.markPending(URL, intent.getId().toString(), NOW);
		stored.put(intent.getId(), intent);
		return intent;
	}

	private void paystackSays(PaymentIntent intent, Outcome outcome) {
		when(gateway.verify(intent.getId().toString()))
			.thenReturn(new Verification(outcome, intent.getId().toString(), 500_000, "NGN",
					outcome == Outcome.SUCCESS ? NOW : null, "Test"));
	}

	private PaymentIntent existing(String key, long kobo) {
		PaymentIntent first = PaymentIntent.initiate(tenant, Money.ofKobo(kobo), key, GatewayProvider.PAYSTACK, NOW);
		when(intents.findByIdempotencyKey(first.getIdempotencyKey())).thenReturn(Optional.of(first));
		return first;
	}

	private void duplicateKey() {
		// doThrow, not when(...): re-stubbing with when() would call the earlier answer with a null argument.
		doThrow(new DataIntegrityViolationException("duplicate",
				new ConstraintViolationException("duplicate", null, "uk_payment_intents_idempotency_key")))
			.when(intents)
			.saveAndFlush(any());
	}

}
