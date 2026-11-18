package com.theodomart.energy.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One webhook as it arrived (DESIGN 6.2). Read only through JPA: rows are inserted with ON CONFLICT and marked
 * processed by a guarded UPDATE in {@code WebhookReceiptRepository}, so the database decides both races.
 */
@Entity
@Immutable
@Table(name = "webhook_receipts")
public class WebhookReceipt {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private GatewayProvider gateway;

	@Column(name = "event_id", nullable = false)
	private String eventId;

	@Column(name = "signature_valid", nullable = false)
	private boolean signatureValid;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String payload;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	@Column(name = "processed_at")
	private Instant processedAt;

	protected WebhookReceipt() {
		// for JPA
	}

	public UUID getId() {
		return id;
	}

	public GatewayProvider getGateway() {
		return gateway;
	}

	public String getEventId() {
		return eventId;
	}

	public boolean isSignatureValid() {
		return signatureValid;
	}

	public String getPayload() {
		return payload;
	}

	public Instant getReceivedAt() {
		return receivedAt;
	}

	public Instant getProcessedAt() {
		return processedAt;
	}

	public boolean isProcessed() {
		return processedAt != null;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof WebhookReceipt other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

}
