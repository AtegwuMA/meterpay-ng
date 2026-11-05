package com.theodomart.energy.property.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tenancies")
public class Tenancy {

	@Id
	private UUID id;

	@Column(name = "unit_id", nullable = false, updatable = false)
	private UUID unitId;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "starts_on", nullable = false, updatable = false)
	private LocalDate startsOn;

	@Column(name = "ends_on")
	private LocalDate endsOn;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TenancyStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Tenancy() {
		// for JPA
	}

	private Tenancy(UUID unitId, UUID tenantId, LocalDate startsOn, Instant now) {
		this.id = UUID.randomUUID();
		this.unitId = Objects.requireNonNull(unitId, "unitId");
		this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
		this.startsOn = Objects.requireNonNull(startsOn, "startsOn");
		this.status = TenancyStatus.ACTIVE;
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}

	public static Tenancy start(UUID unitId, UUID tenantId, LocalDate startsOn, Instant now) {
		return new Tenancy(unitId, tenantId, startsOn, now);
	}

	public void end(LocalDate endsOn, Instant now) {
		if (status != TenancyStatus.ACTIVE) {
			throw new IllegalStateException("Only an active tenancy can be ended");
		}
		if (endsOn.isBefore(startsOn)) {
			throw new IllegalArgumentException("A tenancy cannot end before it starts");
		}
		this.endsOn = endsOn;
		this.status = TenancyStatus.ENDED;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUnitId() {
		return unitId;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public LocalDate getStartsOn() {
		return startsOn;
	}

	public LocalDate getEndsOn() {
		return endsOn;
	}

	public TenancyStatus getStatus() {
		return status;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof Tenancy other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "Tenancy[id=" + id + ", unitId=" + unitId + ", status=" + status + "]";
	}

}
