package com.theodomart.energy.payment.serviceImpl;

import com.theodomart.energy.common.error.InvalidRequestException;
import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.EntryType;
import com.theodomart.energy.ledger.service.JournalEntryCommand;
import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.PostingLine;
import com.theodomart.energy.payment.config.PaystackProperties;
import com.theodomart.energy.payment.domain.GatewayProvider;
import com.theodomart.energy.payment.domain.PaymentIntent;
import com.theodomart.energy.payment.domain.PaymentStatus;
import com.theodomart.energy.payment.domain.WebhookReceipt;
import com.theodomart.energy.payment.repository.PaymentIntentRepository;
import com.theodomart.energy.payment.repository.WebhookReceiptRepository;
import com.theodomart.energy.payment.service.InvalidWebhookSignatureException;
import com.theodomart.energy.payment.service.PaymentGateway;
import com.theodomart.energy.payment.service.PaymentGateway.Verification;
import com.theodomart.energy.payment.service.PaystackWebhookService;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Not @Transactional as a whole, for the same reason as top-ups: the call to Paystack sits between two short
 * transactions. The receipt is committed first, so a crash mid-way leaves an unprocessed receipt and Paystack's
 * retry finishes the job.
 */
@Service
public class PaystackWebhookServiceImpl implements PaystackWebhookService {

	static final String CHARGE_SUCCESS = "charge.success";

	static final String TOP_UP_KEY_PREFIX = "topup:";

	private static final Logger log = LoggerFactory.getLogger(PaystackWebhookServiceImpl.class);

	private static final String HMAC = "HmacSHA512";

	private static final int MAX_EVENT_ID_LENGTH = 200;

	private static final String CURRENCY = "NGN";

	private final WebhookReceiptRepository receipts;

	private final PaymentIntentRepository intents;

	private final PaymentGateway gateway;

	private final LedgerService ledger;

	private final ObjectMapper json;

	private final TransactionTemplate transactions;

	private final Clock clock;

	private final SecretKeySpec signingKey;

	public PaystackWebhookServiceImpl(WebhookReceiptRepository receipts, PaymentIntentRepository intents,
			PaymentGateway gateway, LedgerService ledger, ObjectMapper json, PaystackProperties properties,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.receipts = receipts;
		this.intents = intents;
		this.gateway = gateway;
		this.ledger = ledger;
		this.json = json;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
		// Paystack signs with the same secret key we call it with: there is no separate webhook secret.
		this.signingKey = new SecretKeySpec(properties.secretKey().getBytes(StandardCharsets.UTF_8), HMAC);
	}

	@Override
	public void receive(byte[] rawBody, String signature) {
		byte[] body = rawBody == null ? new byte[0] : rawBody;
		JsonNode event = parse(body);
		if (!signatureMatches(body, signature)) {
			recordForgery(body, event);
			throw new InvalidWebhookSignatureException();
		}
		if (event == null || !event.isObject() || text(event, "event") == null) {
			log.warn("Signed Paystack webhook is not an event we can read");
			throw new InvalidRequestException("The webhook body is not a Paystack event.");
		}

		WebhookReceipt receipt = record(body, event);
		if (receipt.isProcessed()) {
			log.info("Paystack webhook already handled: eventId={}", receipt.getEventId());
			return;
		}
		String type = text(event, "event");
		if (!CHARGE_SUCCESS.equals(type)) {
			// Refunds, transfers and the rest are not ours to act on yet. Acknowledged, so Paystack stops sending.
			finish(receipt, "event type " + type + " is not handled");
			return;
		}

		Optional<UUID> intentId = intentIdOf(event);
		Optional<PaymentIntent> intent = intentId.flatMap(intents::findById);
		if (intent.isEmpty()) {
			// Not ours (another integration on the same Paystack account) or nonsense. Retrying will not help.
			log.warn("Paystack charge matches no top-up: eventId={} reference={}", receipt.getEventId(),
					text(event.path("data"), "reference"));
			finish(receipt, "no matching intent");
			return;
		}
		if (intent.get().getStatus() == PaymentStatus.PAID) {
			finish(receipt, "intent already paid");
			return;
		}

		// The webhook only says "look". What Paystack answers to a call we make is what we believe (DESIGN 8.1). A
		// failure here throws, the receipt stays unprocessed, and Paystack delivers again later.
		Verification verification = gateway.verify(intentId.get().toString());
		transactions.executeWithoutResult(status -> {
			PaymentIntent locked = intents.findByIdForUpdate(intentId.get()).orElseThrow();
			settle(locked, verification);
			receipts.markProcessed(receipt.getId(), clock.instant());
		});
	}

	/** Called under the intent's row lock, so whatever another confirmation did is already visible here. */
	private void settle(PaymentIntent intent, Verification verification) {
		UUID id = intent.getId();
		switch (verification.outcome()) {
			case SUCCESS -> {
				if (intent.getStatus() == PaymentStatus.PAID) {
					log.info("Top-up already paid, nothing to do: intentId={}", id);
					return;
				}
				if (intent.getStatus() != PaymentStatus.PAYMENT_PENDING) {
					// Paid at Paystack, but the intent already gave up (expired) or was never sent. Money is held
					// for the tenant and must not vanish, but no automatic move fits: a person decides.
					log.error("Paystack reports a payment for a top-up that is {}: intentId={} amountKobo={}",
							intent.getStatus(), id, verification.amountKobo());
					return;
				}
				String mismatch = mismatch(intent, verification);
				if (mismatch != null) {
					intent.markFailed(mismatch, clock.instant());
					intents.saveAndFlush(intent);
					log.error("Top-up failed, Paystack confirmed something else: intentId={} {}", id, mismatch);
					return;
				}
				intent.markPaid(clock.instant());
				intents.saveAndFlush(intent);
				// Same transaction as PAID, and a key unique to the intent: even if both checks above were somehow
				// passed twice, the ledger posts the credit once (DESIGN 7.1).
				ledger.post(topUpEntry(intent));
				log.info("Top-up paid and wallet credited: intentId={} amountKobo={}", id, intent.getAmount().kobo());
			}
			case FAILED -> {
				if (intent.getStatus() != PaymentStatus.PAYMENT_PENDING) {
					return;
				}
				intent.markFailed("Gateway reported the payment failed: " + verification.providerMessage(),
						clock.instant());
				intents.saveAndFlush(intent);
				log.info("Top-up failed at the gateway: intentId={}", id);
			}
			// Not finished at Paystack yet. The reconciliation job asks again (M4-05).
			case PENDING -> log.info("Top-up still pending at the gateway: intentId={}", id);
		}
	}

	/** A "success" for another amount, currency or payment is not this payment's success. */
	static String mismatch(PaymentIntent intent, Verification verification) {
		if (!intent.getId().toString().equals(verification.reference())) {
			return "Gateway confirmed reference " + verification.reference() + ", not this intent";
		}
		if (!CURRENCY.equals(verification.currency()) || verification.amountKobo() != intent.getAmount().kobo()) {
			return "Gateway confirmed " + verification.amountKobo() + " kobo in " + verification.currency()
					+ ", expected " + intent.getAmount().kobo() + " kobo in " + CURRENCY;
		}
		return null;
	}

	static JournalEntryCommand topUpEntry(PaymentIntent intent) {
		return new JournalEntryCommand(EntryType.WALLET_TOPUP, TOP_UP_KEY_PREFIX + intent.getId(), "PAYMENT_INTENT",
				intent.getId(), "Wallet top-up",
				List.of(PostingLine.debit(AccountRef.gatewayClearing(), intent.getAmount()),
						PostingLine.credit(AccountRef.tenantWallet(intent.getTenantId()), intent.getAmount())));
	}

	/**
	 * HMAC-SHA512 of the raw bytes, as lowercase hex, compared in constant time so the time taken does not tell an
	 * attacker how many leading characters they got right.
	 */
	boolean signatureMatches(byte[] body, String signature) {
		if (signature == null || signature.isBlank()) {
			return false;
		}
		byte[] expected = HexFormat.of().formatHex(hmac(body)).getBytes(StandardCharsets.US_ASCII);
		byte[] given = signature.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(expected, given);
	}

	private byte[] hmac(byte[] body) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(signingKey);
			return mac.doFinal(body);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("HmacSHA512 is not available", ex);
		}
	}

	/**
	 * Paystack's events carry no id of their own, so the event is named by its type and Paystack's transaction id:
	 * the same charge confirmed twice is one event, and a later refund of it is another.
	 */
	static String eventIdOf(JsonNode event) {
		JsonNode data = event.path("data");
		String subject = text(data, "id");
		if (subject == null) {
			subject = text(data, "reference");
		}
		String id = text(event, "event") + ":" + (subject == null ? "unknown" : subject);
		return id.length() > MAX_EVENT_ID_LENGTH ? id.substring(0, MAX_EVENT_ID_LENGTH) : id;
	}

	private WebhookReceipt record(byte[] body, JsonNode event) {
		String eventId = eventIdOf(event);
		return transactions.execute(status -> {
			receipts.insertSignedIfAbsent(UUID.randomUUID(), GatewayProvider.PAYSTACK.name(), eventId,
					new String(body, StandardCharsets.UTF_8), clock.instant());
			return receipts.findSignedPaystackEvent(eventId).orElseThrow();
		});
	}

	private void finish(WebhookReceipt receipt, String why) {
		transactions.executeWithoutResult(status -> receipts.markProcessed(receipt.getId(), clock.instant()));
		log.info("Paystack webhook acknowledged without action: eventId={} reason={}", receipt.getEventId(), why);
	}

	/**
	 * Kept as evidence of who is probing the endpoint. Storing it must never change the answer, which is 401
	 * whatever happens here.
	 */
	private void recordForgery(byte[] body, JsonNode event) {
		String eventId = event != null && event.isObject() && text(event, "event") != null ? eventIdOf(event)
				: "unreadable";
		String payload = event != null ? new String(body, StandardCharsets.UTF_8)
				: json.createObjectNode().put("unreadable", true).put("bytes", body.length).toString();
		try {
			transactions.executeWithoutResult(status -> receipts.insertUnsigned(UUID.randomUUID(),
					GatewayProvider.PAYSTACK.name(), eventId, payload, clock.instant()));
		}
		catch (DataAccessException ex) {
			log.warn("Could not record a badly signed webhook: {}", ex.getClass().getSimpleName());
		}
		log.warn("Paystack webhook refused, bad signature: eventId={}", eventId);
	}

	private JsonNode parse(byte[] body) {
		try {
			return json.readTree(body);
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	private static Optional<UUID> intentIdOf(JsonNode event) {
		String reference = text(event.path("data"), "reference");
		if (reference == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(UUID.fromString(reference));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (!value.isString() && !value.isNumber()) {
			return null;
		}
		String text = value.asString();
		return text.isBlank() ? null : text;
	}

}
