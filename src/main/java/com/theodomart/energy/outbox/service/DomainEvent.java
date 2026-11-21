package com.theodomart.energy.outbox.service;

import java.util.UUID;

/**
 * Something that happened in a module, for other modules to react to (DESIGN 8.4). Implemented by the records in
 * each module's {@code event} package, which are the contract its consumers read. Every component of the record
 * becomes the event's payload; these three methods only address it.
 */
public interface DomainEvent {

	/** What kind of thing changed, such as {@code PAYMENT_INTENT}. */
	String aggregateType();

	/** Which one. Also the Kafka key, so every event about one aggregate lands on one partition. */
	UUID aggregateId();

	/** For example {@code WalletToppedUp}. Consumers branch on it, so a published name never changes. */
	String eventType();

}
