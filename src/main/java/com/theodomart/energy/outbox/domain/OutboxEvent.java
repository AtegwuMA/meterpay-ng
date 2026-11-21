package com.theodomart.energy.outbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One event waiting to be published, or already published (DESIGN 6.2). Only the relay changes it. */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

	@Id
	private UUID id;

	@Column(name = "aggregate_type", nullable = false, updatable = false)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false, updatable = false)
	private UUID aggregateId;

	@Column(name = "event_type", nullable = false, updatable = false)
	private String eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false)
	private String payload;

	@Column(name = "correlation_id", nullable = false, updatable = false)
	private String correlationId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(nullable = false)
	private int attempts;

	// Wrapper type on purpose: null tells Spring Data the entity is new, so save() persists instead of merging.
	@Version
	private Long version;

	protected OutboxEvent() {
		// for JPA
	}

	public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload,
			String correlationId, Instant now) {
		this.id = UUID.randomUUID();
		this.aggregateType = requireText(aggregateType, "aggregateType");
		this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
		this.eventType = requireText(eventType, "eventType");
		this.payload = Objects.requireNonNull(payload, "payload");
		this.correlationId = requireText(correlationId, "correlationId");
		this.createdAt = micros(now);
	}

	/** Kafka acknowledged it. Done once: a second call would hide a relay bug. */
	public void markPublished(Instant now) {
		if (publishedAt != null) {
			throw new IllegalStateException("Outbox event " + id + " is already published");
		}
		attempts++;
		publishedAt = micros(now);
	}

	/** A send that failed. The event stays unpublished and is tried again on a later poll. */
	public void recordFailedAttempt() {
		attempts++;
	}

	public boolean isPublished() {
		return publishedAt != null;
	}

	private static Instant micros(Instant now) {
		return Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	public UUID getId() {
		return id;
	}

	public String getAggregateType() {
		return aggregateType;
	}

	public UUID getAggregateId() {
		return aggregateId;
	}

	public String getEventType() {
		return eventType;
	}

	public String getPayload() {
		return payload;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public int getAttempts() {
		return attempts;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof OutboxEvent other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "OutboxEvent[id=" + id + ", eventType=" + eventType + ", aggregateId=" + aggregateId + "]";
	}

}
