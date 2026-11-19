package com.theodomart.energy.payment.serviceImpl;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.common.error.IdempotencyConflictException;
import com.theodomart.energy.common.error.InvalidRequestException;
import com.theodomart.energy.common.error.ResourceNotFoundException;
import com.theodomart.energy.common.persistence.ConstraintViolations;
import com.theodomart.energy.identity.service.IdentityService;
import com.theodomart.energy.payment.domain.GatewayProvider;
import com.theodomart.energy.payment.domain.PaymentIntent;
import com.theodomart.energy.payment.domain.PaymentStatus;
import com.theodomart.energy.payment.repository.PaymentIntentRepository;
import com.theodomart.energy.payment.service.PaymentGateway;
import com.theodomart.energy.payment.service.PaymentGateway.Initialization;
import com.theodomart.energy.payment.service.PaymentGateway.Verification;
import com.theodomart.energy.payment.service.PaymentGatewayException;
import com.theodomart.energy.payment.service.TopUpService;
import com.theodomart.energy.payment.service.TopUpView;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Not @Transactional as a whole, on purpose: the call to Paystack happens between two short transactions, so no
 * database connection or row lock is held while waiting on the network.
 */
@Service
public class TopUpServiceImpl implements TopUpService {

	private static final Logger log = LoggerFactory.getLogger(TopUpServiceImpl.class);

	private final PaymentIntentRepository intents;

	private final PaymentGateway gateway;

	private final IdentityService identity;

	private final TopUpSettlement settlement;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public TopUpServiceImpl(PaymentIntentRepository intents, PaymentGateway gateway, IdentityService identity,
			TopUpSettlement settlement, PlatformTransactionManager transactionManager, Clock clock) {
		this.intents = intents;
		this.gateway = gateway;
		this.identity = identity;
		this.settlement = settlement;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	@Override
	public StartedTopUp start(UUID tenantId, String clientKey, Money amount) {
		PaymentIntent intent;
		try {
			intent = PaymentIntent.initiate(tenantId, amount, clientKey, GatewayProvider.PAYSTACK, clock.instant());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage() + ".");
		}

		// 1. Commit the intent before anything leaves the building, so a crash during the call leaves a row to
		// reconcile. The UNIQUE key decides between concurrent requests with the same key, not a lookup first.
		try {
			transactions.executeWithoutResult(status -> intents.saveAndFlush(intent));
		}
		catch (DataIntegrityViolationException ex) {
			if (!"uk_payment_intents_idempotency_key".equals(ConstraintViolations.constraintName(ex))) {
				throw ex;
			}
			return new StartedTopUp(replay(intent), false);
		}
		log.info("Top-up intent created: intentId={} amountKobo={}", intent.getId(), amount.kobo());

		// 2. Ask Paystack, outside any transaction. Our own id is the reference, so a payment can always be traced.
		String email = identity.currentUser(tenantId).email();
		Initialization initialization;
		try {
			initialization = gateway.initialize(intent.getId().toString(), amount, email);
		}
		catch (PaymentGatewayException ex) {
			recordGatewayFailure(intent.getId(), ex);
			throw ex;
		}

		// 3. Record the page to pay on. A second transaction, so a failure here cannot undo step 1.
		PaymentIntent pending = transactions.execute(status -> {
			PaymentIntent current = intents.findById(intent.getId()).orElseThrow();
			current.markPending(initialization.authorizationUrl(), initialization.reference(), clock.instant());
			return intents.saveAndFlush(current);
		});
		log.info("Top-up intent pending payment: intentId={}", pending.getId());
		return new StartedTopUp(toView(pending), true);
	}

	@Override
	public TopUpView status(UUID tenantId, UUID intentId) {
		PaymentIntent intent = transactions.execute(status -> intents.findById(intentId))
			.filter(found -> found.getTenantId().equals(tenantId))
			// Someone else's top-up looks exactly like one that does not exist (DESIGN 10).
			.orElseThrow(() -> new ResourceNotFoundException("Top-up " + intentId + " was not found."));
		if (intent.getStatus() != PaymentStatus.PAYMENT_PENDING) {
			return toView(intent);
		}

		// Outside any transaction, as everywhere in this module.
		Verification verification;
		try {
			verification = gateway.verify(intentId.toString());
		}
		catch (PaymentGatewayException ex) {
			// The tenant asked for the status, and the stored one is still true. The webhook or the reconciliation
			// job will settle it later.
			log.warn("Top-up status served without a verify, gateway unavailable: intentId={} providerStatus={}",
					intentId, ex.providerStatus());
			return toView(intent);
		}
		PaymentIntent settled = transactions.execute(status -> {
			PaymentIntent locked = intents.findByIdForUpdate(intentId).orElseThrow();
			// A webhook may have settled it while we were asking.
			if (locked.getStatus() == PaymentStatus.PAYMENT_PENDING) {
				settlement.apply(locked, verification);
			}
			return locked;
		});
		return toView(settled);
	}

	/**
	 * The same key again. Only a repeat if it asks for the same amount; anything else would hide a different request
	 * behind the first one's answer. A repeat gets the intent as it is now, which may still be INITIATED if the first
	 * request is still talking to Paystack.
	 */
	private TopUpView replay(PaymentIntent attempted) {
		PaymentIntent existing = transactions.execute(status -> intents
			.findByIdempotencyKey(attempted.getIdempotencyKey())
			.orElseThrow(() -> new IllegalStateException("Idempotency key was taken but no intent has it")));
		if (!existing.getAmount().equals(attempted.getAmount())) {
			log.warn("Idempotency key reused for a different amount: intentId={}", existing.getId());
			throw new IdempotencyConflictException();
		}
		log.info("Top-up request replayed: intentId={} status={}", existing.getId(), existing.getStatus());
		return toView(existing);
	}

	/**
	 * A refusal (4xx, or an answer we cannot use) will not change on retry, so the intent fails now. A timeout or a
	 * Paystack outage leaves it INITIATED: the payment may exist at Paystack, and only verifying can tell (M4-05).
	 */
	private void recordGatewayFailure(UUID intentId, PaymentGatewayException ex) {
		if (ex.isRetryable()) {
			log.warn("Top-up intent left for reconciliation, gateway unavailable: intentId={} providerStatus={}",
					intentId, ex.providerStatus());
			return;
		}
		transactions.executeWithoutResult(status -> {
			PaymentIntent current = intents.findById(intentId).orElseThrow();
			current.markFailed("Gateway refused initialization (status " + ex.providerStatus() + ")", clock.instant());
			intents.saveAndFlush(current);
		});
		log.warn("Top-up intent failed, gateway refused: intentId={} providerStatus={}", intentId, ex.providerStatus());
	}

	static TopUpView toView(PaymentIntent intent) {
		return new TopUpView(intent.getId(), intent.getStatus().name(), intent.getAmount().kobo(),
				intent.getStatus() == PaymentStatus.FAILED ? null : intent.getAuthorizationUrl(), intent.getCreatedAt());
	}

}
