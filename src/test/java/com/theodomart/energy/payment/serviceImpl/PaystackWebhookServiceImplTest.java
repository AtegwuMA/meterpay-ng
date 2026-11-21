package com.theodomart.energy.payment.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.common.error.InvalidRequestException;
import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.Direction;
import com.theodomart.energy.ledger.service.JournalEntryCommand;
import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.PostingLine;
import com.theodomart.energy.outbox.service.OutboxWriter;
import com.theodomart.energy.payment.config.PaystackProperties;
import com.theodomart.energy.payment.domain.GatewayProvider;
import com.theodomart.energy.payment.domain.PaymentEventType;
import com.theodomart.energy.payment.domain.PaymentIntent;
import com.theodomart.energy.payment.domain.PaymentStatus;
import com.theodomart.energy.payment.domain.WebhookReceipt;
import com.theodomart.energy.payment.event.WalletToppedUp;
import com.theodomart.energy.payment.repository.PaymentIntentRepository;
import com.theodomart.energy.payment.repository.WebhookReceiptRepository;
import com.theodomart.energy.payment.service.InvalidWebhookSignatureException;
import com.theodomart.energy.payment.service.PaymentGateway;
import com.theodomart.energy.payment.service.PaymentGateway.Outcome;
import com.theodomart.energy.payment.service.PaymentGateway.Verification;
import com.theodomart.energy.payment.service.PaymentGatewayException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class PaystackWebhookServiceImplTest {

	private static final String SECRET = "sk_test_webhook_secret";

	private static final Instant NOW = Instant.parse("2026-11-18T09:00:00Z");

	private final WebhookReceiptRepository receipts = mock();

	private final PaymentIntentRepository intents = mock();

	private final PaymentGateway gateway = mock();

	private final LedgerService ledger = mock();

	private final OutboxWriter outbox = mock();

	private final PaymentEventRecorder events = mock();

	private final JsonMapper json = JsonMapper.builder().build();

	private final PaystackWebhookServiceImpl webhooks = new PaystackWebhookServiceImpl(receipts, intents, gateway,
			new TopUpSettlement(intents, ledger, outbox, Clock.fixed(NOW, ZoneOffset.UTC)), events, json, new PaystackProperties("http://paystack.invalid", SECRET, null, Duration.ofSeconds(1),
					Duration.ofSeconds(1)),
			mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));

	private final UUID tenant = UUID.randomUUID();

	private final WebhookReceipt receipt = mock();

	private PaymentIntent intent;

	@BeforeEach
	void aPendingTopUpAndAFreshReceipt() {
		intent = PaymentIntent.initiate(tenant, Money.ofKobo(500_000), "k", GatewayProvider.PAYSTACK, NOW);
		intent.markPending("https://checkout.paystack.com/x", intent.getId().toString(), NOW);
		when(intents.findById(intent.getId())).thenAnswer(call -> Optional.of(intent));
		when(intents.findByIdForUpdate(intent.getId())).thenAnswer(call -> Optional.of(intent));
		when(receipt.getId()).thenReturn(UUID.randomUUID());
		when(receipt.getEventId()).thenReturn("charge.success:1");
		when(receipts.findSignedPaystackEvent(anyString())).thenReturn(Optional.of(receipt));
	}

	@Test
	void aConfirmedChargeMarksTheIntentPaidAndCreditsTheWallet() {
		paystackSays(Outcome.SUCCESS, 500_000, "NGN");

		receive(charge(intent.getId().toString()));

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PAID);
		verify(ledger).post(TopUpSettlement.topUpEntry(intent));
		verify(outbox).write(new WalletToppedUp(intent.getId(), tenant, 500_000, intent.getPaidAt()));
		verify(receipts).markProcessed(eq(receipt.getId()), any());
	}

	@Test
	void everySignedDeliveryIsLoggedAgainstItsIntentRepeatsToo() {
		when(receipt.isProcessed()).thenReturn(true);

		receive(charge(intent.getId().toString()));
		receive(charge("T-not-ours"));

		verify(events).record(eq(intent.getId()), eq(PaymentEventType.WEBHOOK_RECEIVED),
				org.mockito.ArgumentMatchers.argThat(payload -> payload.get("repeat").asBoolean()));
		verify(events).record(org.mockito.ArgumentMatchers.isNull(), eq(PaymentEventType.WEBHOOK_RECEIVED), any());
	}

	@Test
	void aRepeatOfAHandledEventDoesNothing() {
		when(receipt.isProcessed()).thenReturn(true);

		receive(charge(intent.getId().toString()));

		verify(gateway, never()).verify(any());
		verify(receipts, never()).markProcessed(any(), any());
	}

	@Test
	void anIntentAlreadyPaidIsNotVerifiedAgain() {
		intent.markPaid(NOW);

		receive(charge(intent.getId().toString()));

		verify(gateway, never()).verify(any());
		verify(receipts).markProcessed(eq(receipt.getId()), any());
	}

	@Test
	void aFailedPaymentFailsThePendingIntent() {
		paystackSays(Outcome.FAILED, 500_000, "NGN");

		receive(charge(intent.getId().toString()));

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(intent.getFailureReason()).contains("Declined");
		verify(ledger, never()).post(any());
		verify(outbox, never()).write(any());
	}

	@Test
	void aPaymentStillInProgressChangesNothing() {
		paystackSays(Outcome.PENDING, 500_000, "NGN");

		receive(charge(intent.getId().toString()));

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PAYMENT_PENDING);
		verify(ledger, never()).post(any());
		verify(receipts).markProcessed(eq(receipt.getId()), any());
	}

	@Test
	void moneyForAnIntentThatAlreadyFailedIsLeftForAPersonNotCredited() {
		paystackSays(Outcome.SUCCESS, 500_000, "NGN");
		// Expired between the webhook's first look and the lock: settle must look again under the lock.
		when(intents.findByIdForUpdate(intent.getId())).thenAnswer(call -> {
			intent.markFailed("Expired", NOW);
			return Optional.of(intent);
		});

		receive(charge(intent.getId().toString()));

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.FAILED);
		verify(ledger, never()).post(any());
	}

	@Test
	void aFailureReportedForAFinishedIntentIsIgnored() {
		paystackSays(Outcome.FAILED, 500_000, "NGN");
		when(intents.findByIdForUpdate(intent.getId())).thenAnswer(call -> {
			intent.markPaid(NOW);
			return Optional.of(intent);
		});

		receive(charge(intent.getId().toString()));

		assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PAID);
	}

	@Test
	void aSecondConfirmationThatLostTheRaceFindsItPaidAndPostsNothing() {
		paystackSays(Outcome.SUCCESS, 500_000, "NGN");
		when(intents.findByIdForUpdate(intent.getId())).thenAnswer(call -> {
			intent.markPaid(NOW);
			return Optional.of(intent);
		});

		receive(charge(intent.getId().toString()));

		verify(ledger, never()).post(any());
	}

	@Test
	void aGatewayOutageLeavesTheReceiptForPaystacksRetry() {
		when(gateway.verify(any())).thenThrow(PaymentGatewayException.rejected(503));

		assertThatThrownBy(() -> receive(charge(intent.getId().toString())))
			.isInstanceOf(PaymentGatewayException.class);

		verify(receipts, never()).markProcessed(any(), any());
	}

	@Test
	void aReferenceThatIsNotAnIntentIsAcknowledged() {
		receive(charge("T123456789"));
		receive(charge(UUID.randomUUID().toString()));

		verify(gateway, never()).verify(any());
	}

	@Test
	void otherEventTypesAreAcknowledgedWithoutAction() {
		receive("{\"event\": \"transfer.success\", \"data\": {\"id\": 5}}");

		verify(gateway, never()).verify(any());
		verify(receipts).markProcessed(eq(receipt.getId()), any());
	}

	@Test
	void aSignedBodyThatIsNotAnEventIsRefused() {
		assertThatThrownBy(() -> receive("[1, 2]")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> receive("{\"data\": {}}")).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aBadSignatureIsRecordedThenRefused() {
		byte[] body = charge(intent.getId().toString()).getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> webhooks.receive(body, "00ff")).isInstanceOf(InvalidWebhookSignatureException.class);

		verify(receipts).insertUnsigned(any(), eq("PAYSTACK"), eq("charge.success:42"), anyString(), any());
		verify(receipts, never()).insertSignedIfAbsent(any(), any(), any(), any(), any());
	}

	@Test
	void anUnreadableForgeryIsKeptAsAShortNote() {
		byte[] body = "not json".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> webhooks.receive(body, null)).isInstanceOf(InvalidWebhookSignatureException.class);

		verify(receipts).insertUnsigned(any(), eq("PAYSTACK"), eq("unreadable"),
				eq("{\"unreadable\":true,\"bytes\":8}"), any());
	}

	@Test
	void failingToRecordAForgeryStillRefusesIt() {
		when(receipts.insertUnsigned(any(), any(), any(), any(), any()))
			.thenThrow(new DataAccessResourceFailureException("down"));

		assertThatThrownBy(() -> webhooks.receive(null, "abc")).isInstanceOf(InvalidWebhookSignatureException.class);
	}

	@Test
	void theSignatureIsTheHmacOfTheExactBytesInAnyCase() throws Exception {
		byte[] body = "{\"event\": \"charge.success\"}".getBytes(StandardCharsets.UTF_8);
		String signature = sign(body);

		assertThat(webhooks.signatureMatches(body, signature)).isTrue();
		assertThat(webhooks.signatureMatches(body, signature.toUpperCase(Locale.ROOT))).isTrue();
		assertThat(webhooks.signatureMatches("{\"event\":\"charge.success\"}".getBytes(StandardCharsets.UTF_8),
				signature))
			.as("the same JSON with different spacing is a different body")
			.isFalse();
		assertThat(webhooks.signatureMatches(body, signature.substring(2))).isFalse();
		assertThat(webhooks.signatureMatches(body, " ")).isFalse();
		assertThat(webhooks.signatureMatches(body, null)).isFalse();
	}

	@Test
	void anEventIsNamedByItsTypeAndPaystacksTransaction() {
		assertThat(PaystackWebhookServiceImpl.eventIdOf(json.readTree("{\"event\": \"charge.success\", "
				+ "\"data\": {\"id\": 302961, \"reference\": \"r\"}}")))
			.isEqualTo("charge.success:302961");
		assertThat(PaystackWebhookServiceImpl
			.eventIdOf(json.readTree("{\"event\": \"charge.success\", \"data\": {\"reference\": \"r-1\"}}")))
			.isEqualTo("charge.success:r-1");
		assertThat(PaystackWebhookServiceImpl.eventIdOf(json.readTree("{\"event\": \"charge.success\"}")))
			.isEqualTo("charge.success:unknown");
		assertThat(PaystackWebhookServiceImpl.eventIdOf(
				json.readTree("{\"event\": \"charge.success\", \"data\": {\"reference\": \"" + "r".repeat(300) + "\"}}")))
			.hasSize(200);
	}

	@Test
	void aConfirmationOnlyCountsForTheSameReferenceAmountAndCurrency() {
		String id = intent.getId().toString();

		assertThat(TopUpSettlement.mismatch(intent, verification(id, 500_000, "NGN"))).isNull();
		assertThat(TopUpSettlement.mismatch(intent, verification("other", 500_000, "NGN")))
			.contains("reference");
		assertThat(TopUpSettlement.mismatch(intent, verification(id, 499_999, "NGN"))).contains("499999");
		assertThat(TopUpSettlement.mismatch(intent, verification(id, 500_000, "GHS"))).contains("GHS");
	}

	@Test
	void aTopUpMovesMoneyFromClearingToTheWalletUnderAKeyOfItsOwn() {
		JournalEntryCommand entry = TopUpSettlement.topUpEntry(intent);

		assertThat(entry.idempotencyKey()).isEqualTo("topup:" + intent.getId());
		assertThat(entry.referenceId()).isEqualTo(intent.getId());
		assertThat(entry.lines()).containsExactly(
				new PostingLine(AccountRef.gatewayClearing(), Direction.DEBIT, Money.ofKobo(500_000)),
				new PostingLine(AccountRef.tenantWallet(tenant), Direction.CREDIT, Money.ofKobo(500_000)));
	}

	private void paystackSays(Outcome outcome, long kobo, String currency) {
		when(gateway.verify(intent.getId().toString()))
			.thenReturn(new Verification(outcome, intent.getId().toString(), kobo, currency,
					outcome == Outcome.SUCCESS ? NOW : null, outcome == Outcome.FAILED ? "Declined" : "Approved"));
	}

	private static Verification verification(String reference, long kobo, String currency) {
		return new Verification(Outcome.SUCCESS, reference, kobo, currency, NOW, "Approved");
	}

	private void receive(String body) {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		webhooks.receive(bytes, sign(bytes));
	}

	private static String charge(String reference) {
		return """
				{"event": "charge.success", "data": {"id": 42, "reference": "%s", "amount": 500000, "currency": "NGN"}}
				""".formatted(reference);
	}

	private static String sign(byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA512");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
			return HexFormat.of().formatHex(mac.doFinal(body));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
