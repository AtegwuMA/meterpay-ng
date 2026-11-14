package com.theodomart.energy.payment.domain;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.common.error.IllegalStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * One attempt to put money into a tenant's wallet (DESIGN 7.1). Its status changes only through the methods here,
 * which all go through {@link #transitionTo}, so no flow can skip a step or reach PAID twice.
 */
@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

	static final int MAX_FAILURE_REASON_LENGTH = 255;

	static final int MAX_CLIENT_KEY_LENGTH = 255;

	@Id
	private UUID id;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "amount_kobo", nullable = false, updatable = false)
	private long amountKobo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentStatus status;

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private GatewayProvider gateway;

	@Column(name = "gateway_reference")
	private String gatewayReference;

	@Column(name = "authorization_url")
	private String authorizationUrl;

	@Column(name = "failure_reason")
	private String failureReason;

	@Column(name = "paid_at")
	private Instant paidAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	// Wrapper type on purpose: null tells Spring Data the entity is new, so save() persists instead of merging.
	@Version
	private Long version;

	protected PaymentIntent() {
		// for JPA
	}

	private PaymentIntent(UUID tenantId, long amountKobo, String idempotencyKey, GatewayProvider gateway, Instant now) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.amountKobo = amountKobo;
		this.idempotencyKey = idempotencyKey;
		this.gateway = gateway;
		this.status = PaymentStatus.INITIATED;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * A new top-up, not yet sent to the gateway.
	 *
	 * @param clientKey the client's Idempotency-Key header, as sent
	 * @throws IllegalArgumentException if the amount is zero or the key is blank or too long
	 */
	public static PaymentIntent initiate(UUID tenantId, Money amount, String clientKey, GatewayProvider gateway,
			Instant now) {
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(gateway, "gateway");
		if (amount.isZero()) {
			throw new IllegalArgumentException("A top-up must be for more than zero");
		}
		return new PaymentIntent(tenantId, amount.kobo(), idempotencyKeyFor(tenantId, clientKey), gateway,
				micros(now));
	}

	/**
	 * SHA-256 of the tenant and the client's key, as hex (DESIGN 7.1). Scoping by tenant means two tenants who happen
	 * to send the same key get separate intents. Hashing gives a fixed length whatever the client sends. The separator
	 * cannot be confused with the tenant part, because a UUID is always 36 characters.
	 *
	 * @throws IllegalArgumentException if the key is blank or longer than 255 characters
	 */
	public static String idempotencyKeyFor(UUID tenantId, String clientKey) {
		Objects.requireNonNull(tenantId, "tenantId");
		if (clientKey == null || clientKey.isBlank()) {
			throw new IllegalArgumentException("An Idempotency-Key is required");
		}
		if (clientKey.length() > MAX_CLIENT_KEY_LENGTH) {
			throw new IllegalArgumentException("Idempotency-Key is longer than " + MAX_CLIENT_KEY_LENGTH);
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest((tenantId + ":" + clientKey).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is missing from this JVM", ex);
		}
	}

	/** The gateway accepted it and gave the page the tenant pays on. */
	public void markPending(String authorizationUrl, String gatewayReference, Instant now) {
		requireText(authorizationUrl, "authorizationUrl");
		requireText(gatewayReference, "gatewayReference");
		transitionTo(PaymentStatus.PAYMENT_PENDING, now);
		this.authorizationUrl = authorizationUrl;
		this.gatewayReference = gatewayReference;
	}

	/** The gateway confirmed the money arrived. Reached at most once: a second call is refused. */
	public void markPaid(Instant now) {
		transitionTo(PaymentStatus.PAID, now);
		this.paidAt = this.updatedAt;
	}

	/** The gateway refused it, or it expired. The reason is kept for support and shown to nobody but staff. */
	public void markFailed(String reason, Instant now) {
		requireText(reason, "reason");
		transitionTo(PaymentStatus.FAILED, now);
		this.failureReason = reason.length() > MAX_FAILURE_REASON_LENGTH
				? reason.substring(0, MAX_FAILURE_REASON_LENGTH) : reason;
	}

	public void markRefunded(Instant now) {
		transitionTo(PaymentStatus.REFUNDED, now);
	}

	private void transitionTo(PaymentStatus target, Instant now) {
		if (!status.canTransitionTo(target)) {
			throw new IllegalStateTransitionException("payment", status, target);
		}
		this.status = target;
		this.updatedAt = micros(now);
	}

	private static Instant micros(Instant now) {
		return Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public Money getAmount() {
		return Money.ofKobo(amountKobo);
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public GatewayProvider getGateway() {
		return gateway;
	}

	public String getGatewayReference() {
		return gatewayReference;
	}

	public String getAuthorizationUrl() {
		return authorizationUrl;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Instant getPaidAt() {
		return paidAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof PaymentIntent other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "PaymentIntent[id=" + id + ", status=" + status + ", amountKobo=" + amountKobo + "]";
	}

}
