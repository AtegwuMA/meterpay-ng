package com.theodomart.energy.property.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A one-time code for joining a unit. Only its hash is stored, so a database leak exposes no usable codes. */
@Entity
@Table(name = "tenant_invites")
public class TenantInvite {

	@Id
	private UUID id;

	@Column(name = "unit_id", nullable = false, updatable = false)
	private UUID unitId;

	@Column(name = "code_hash", nullable = false, updatable = false)
	private String codeHash;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "accepted_at")
	private Instant acceptedAt;

	@Column(name = "accepted_by")
	private UUID acceptedBy;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected TenantInvite() {
		// for JPA
	}

	private TenantInvite(UUID unitId, String codeHash, UUID createdBy, Instant now, Duration ttl) {
		this.id = UUID.randomUUID();
		this.unitId = Objects.requireNonNull(unitId, "unitId");
		this.codeHash = Objects.requireNonNull(codeHash, "codeHash");
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
		this.createdAt = Objects.requireNonNull(now, "now");
		this.expiresAt = now.plus(ttl);
		this.updatedAt = now;
	}

	public static TenantInvite create(UUID unitId, String codeHash, UUID createdBy, Instant now, Duration ttl) {
		return new TenantInvite(unitId, codeHash, createdBy, now, ttl);
	}

	/** Not accepted, not revoked, not expired. */
	public boolean isRedeemable(Instant now) {
		return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt);
	}

	public void accept(UUID tenantId, Instant now) {
		if (!isRedeemable(now)) {
			throw new IllegalStateException("Only a redeemable invite can be accepted");
		}
		this.acceptedBy = Objects.requireNonNull(tenantId, "tenantId");
		this.acceptedAt = now;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUnitId() {
		return unitId;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getAcceptedAt() {
		return acceptedAt;
	}

	public UUID getAcceptedBy() {
		return acceptedBy;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof TenantInvite other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "TenantInvite[id=" + id + ", unitId=" + unitId + "]";
	}

}
