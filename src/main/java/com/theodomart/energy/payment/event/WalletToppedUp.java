package com.theodomart.energy.payment.event;

import com.theodomart.energy.outbox.service.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A top-up was confirmed and the tenant's wallet credited (DESIGN 8.1). Written in the same transaction as the
 * credit, so it is published exactly when the money is really there.
 *
 * @param paidAt when we confirmed it, the same instant as the intent's {@code paid_at}
 */
public record WalletToppedUp(UUID intentId, UUID tenantId, long amountKobo, Instant paidAt) implements DomainEvent {

	public static final String TYPE = "WalletToppedUp";

	@Override
	public String aggregateType() {
		return "PAYMENT_INTENT";
	}

	@Override
	public UUID aggregateId() {
		return intentId;
	}

	@Override
	public String eventType() {
		return TYPE;
	}

}
