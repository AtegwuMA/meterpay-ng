package com.theodomart.energy.payment.repository;

import com.theodomart.energy.payment.domain.WebhookReceipt;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** No delete: a receipt is evidence of what the gateway sent, or of what someone pretending to be it sent. */
public interface WebhookReceiptRepository extends Repository<WebhookReceipt, UUID> {

	/**
	 * Records a correctly signed webhook unless the same event is already recorded, and returns how many rows it
	 * inserted. The partial unique index decides between concurrent deliveries, not a lookup first.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO webhook_receipts (id, gateway, event_id, signature_valid, payload, received_at)
			VALUES (:id, :gateway, :eventId, TRUE, CAST(:payload AS jsonb), :receivedAt)
			ON CONFLICT (gateway, event_id) WHERE signature_valid DO NOTHING
			""", nativeQuery = true)
	int insertSignedIfAbsent(@Param("id") UUID id, @Param("gateway") String gateway, @Param("eventId") String eventId,
			@Param("payload") String payload, @Param("receivedAt") Instant receivedAt);

	/** A badly signed webhook is kept, never deduplicated and never processed (CHECK in V13). */
	@Modifying
	@Query(value = """
			INSERT INTO webhook_receipts (id, gateway, event_id, signature_valid, payload, received_at)
			VALUES (:id, :gateway, :eventId, FALSE, CAST(:payload AS jsonb), :receivedAt)
			""", nativeQuery = true)
	int insertUnsigned(@Param("id") UUID id, @Param("gateway") String gateway, @Param("eventId") String eventId,
			@Param("payload") String payload, @Param("receivedAt") Instant receivedAt);

	@Query("""
			SELECT r FROM WebhookReceipt r
			WHERE r.gateway = com.theodomart.energy.payment.domain.GatewayProvider.PAYSTACK
				AND r.eventId = :eventId AND r.signatureValid = TRUE
			""")
	Optional<WebhookReceipt> findSignedPaystackEvent(@Param("eventId") String eventId);

	/** Returns 1 the first time and 0 after, so two deliveries finishing together cannot both claim it. */
	@Modifying
	@Query(value = """
			UPDATE webhook_receipts SET processed_at = :now
			WHERE id = :id AND signature_valid AND processed_at IS NULL
			""", nativeQuery = true)
	int markProcessed(@Param("id") UUID id, @Param("now") Instant now);

}
