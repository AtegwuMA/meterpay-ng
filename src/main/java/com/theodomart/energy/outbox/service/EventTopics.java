package com.theodomart.energy.outbox.service;

/** Kafka topic names (DESIGN 8.4). The version is in the name: an incompatible change gets a new topic. */
public final class EventTopics {

	/** Every domain event published through the outbox, keyed by aggregate id. */
	public static final String ENERGY_EVENTS = "energy.events.v1";

	/** Kafka header carrying the event type, so a consumer can skip events it does not handle without parsing. */
	public static final String EVENT_TYPE_HEADER = "X-Event-Type";

	private EventTopics() {
	}

}
