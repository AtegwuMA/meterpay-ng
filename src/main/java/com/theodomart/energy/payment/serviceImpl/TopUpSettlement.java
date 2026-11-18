package com.theodomart.energy.payment.serviceImpl;

import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.EntryType;
import com.theodomart.energy.ledger.service.JournalEntryCommand;
import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.PostingLine;
import com.theodomart.energy.payment.domain.PaymentIntent;
import com.theodomart.energy.payment.domain.PaymentStatus;
import com.theodomart.energy.payment.repository.PaymentIntentRepository;
import com.theodomart.energy.payment.service.PaymentGateway.Verification;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What a verified answer from the gateway does to an intent (DESIGN 7.1). One place, so the webhook (M4-04), the
 * reconciliation job (M4-05) and the status check (M4-06) cannot disagree about when a wallet is credited.
 * <p>
 * Runs inside the caller's transaction, on an intent the caller has locked with
 * {@link PaymentIntentRepository#findByIdForUpdate}: whatever another confirmation did is then already visible.
 */
@Component
public class TopUpSettlement {

	static final String TOP_UP_KEY_PREFIX = "topup:";

	private static final Logger log = LoggerFactory.getLogger(TopUpSettlement.class);

	private static final String CURRENCY = "NGN";

	private final PaymentIntentRepository intents;

	private final LedgerService ledger;

	private final Clock clock;

	public TopUpSettlement(PaymentIntentRepository intents, LedgerService ledger, Clock clock) {
		this.intents = intents;
		this.ledger = ledger;
		this.clock = clock;
	}

	void apply(PaymentIntent intent, Verification verification) {
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
				fail(intent, "Gateway reported the payment failed: " + verification.providerMessage());
			}
			// Not finished at Paystack yet. Whoever asked decides whether waiting longer still makes sense.
			case PENDING -> log.info("Top-up still pending at the gateway: intentId={}", id);
		}
	}

	void fail(PaymentIntent intent, String reason) {
		intent.markFailed(reason, clock.instant());
		intents.saveAndFlush(intent);
		log.info("Top-up failed: intentId={} reason={}", intent.getId(), reason);
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

}
