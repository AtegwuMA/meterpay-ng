package com.theodomart.energy.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One refresh token, known to the server only by its hash. Tokens descending from the same login share a
 * family id, so a stolen and reused token can take the whole chain down with it.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Column(name = "family_id", nullable = false, updatable = false)
	private UUID familyId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "replaced_by")
	private UUID replacedBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected RefreshToken() {
		// for JPA
	}

	private RefreshToken(UUID userId, String tokenHash, UUID familyId, Instant now, Duration ttl) {
		this.id = UUID.randomUUID();
		this.userId = Objects.requireNonNull(userId, "userId");
		this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
		this.familyId = Objects.requireNonNull(familyId, "familyId");
		this.createdAt = Objects.requireNonNull(now, "now");
		this.expiresAt = now.plus(ttl);
		this.updatedAt = now;
	}

	/** The first token of a new family, created at login. */
	public static RefreshToken startFamily(UUID userId, String tokenHash, Instant now, Duration ttl) {
		return new RefreshToken(userId, tokenHash, UUID.randomUUID(), now, ttl);
	}

	/**
	 * Retires this token and returns its successor in the same family. The successor gets a full new
	 * lifetime, so an active user stays signed in while an idle one is logged out after the TTL.
	 */
	public RefreshToken rotate(String nextTokenHash, Instant now, Duration ttl) {
		if (!isUsable(now)) {
			throw new IllegalStateException("Only a usable refresh token can be rotated");
		}
		RefreshToken next = new RefreshToken(userId, nextTokenHash, familyId, now, ttl);
		this.replacedBy = next.id;
		revoke(now);
		return next;
	}

	public void revoke(Instant now) {
		if (revokedAt == null) {
			revokedAt = now;
			updatedAt = now;
		}
	}

	/** Not revoked, not rotated, not expired. */
	public boolean isUsable(Instant now) {
		return revokedAt == null && now.isBefore(expiresAt);
	}

	/** Already rotated or revoked: presenting it again means someone else may hold a copy. */
	public boolean isRetired() {
		return revokedAt != null;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public UUID getFamilyId() {
		return familyId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public UUID getReplacedBy() {
		return replacedBy;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof RefreshToken other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "RefreshToken[id=" + id + ", familyId=" + familyId + ", revokedAt=" + revokedAt + "]";
	}

}
