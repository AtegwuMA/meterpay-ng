package com.theodomart.energy.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One exchange with the gateway, kept as it happened. Insert only: the database refuses changes (V13). */
@Entity
@Immutable
@Table(name = "payment_events")
public class PaymentEvent {

	@Id
	private UUID id;

	@Column(name = "intent_id", updatable = false)
	private UUID intentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, updatable = false)
	private PaymentEventType eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false)
	private String payload;

	@Column(name = "correlation_id", nullable = false, updatable = false)
	private String correlationId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected PaymentEvent() {
		// for JPA
	}

	/**
	 * @param intentId null when the exchange cannot be tied to one of our intents, which is the kind worth keeping
	 * @param payload a JSON object
	 */
	public PaymentEvent(UUID intentId, PaymentEventType eventType, String payload, String correlationId,
			Instant now) {
		this.id = UUID.randomUUID();
		this.intentId = intentId;
		this.eventType = Objects.requireNonNull(eventType, "eventType");
		this.payload = Objects.requireNonNull(payload, "payload");
		this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
		this.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
	}

	public UUID getId() {
		return id;
	}

	public UUID getIntentId() {
		return intentId;
	}

	public PaymentEventType getEventType() {
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

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof PaymentEvent other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

}
