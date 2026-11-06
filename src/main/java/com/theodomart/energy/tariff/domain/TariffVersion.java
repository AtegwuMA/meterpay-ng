package com.theodomart.energy.tariff.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One price, from one moment onwards. It never changes after it is created: purchases point at the version they
 * were priced with, so editing one would silently rewrite why a past purchase cost what it did. A new price is a
 * new version.
 */
@Entity
@Table(name = "tariff_versions")
public class TariffVersion {

	public static final int MAX_VAT_BASIS_POINTS = 10_000;

	/** Below 100%: at exactly 100% the landlord receives nothing and the tenant gets no energy. */
	public static final int MAX_PLATFORM_FEE_BASIS_POINTS = 9_999;

	@Id
	private UUID id;

	@Column(name = "plan_id", nullable = false, updatable = false)
	private UUID planId;

	@Column(name = "rate_kobo_per_kwh", nullable = false, updatable = false)
	private long rateKoboPerKwh;

	@Column(name = "vat_basis_points", nullable = false, updatable = false)
	private int vatBasisPoints;

	@Column(name = "platform_fee_basis_points", nullable = false, updatable = false)
	private int platformFeeBasisPoints;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private Instant effectiveFrom;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected TariffVersion() {
		// for JPA
	}

	private TariffVersion(UUID planId, long rateKoboPerKwh, int vatBasisPoints, int platformFeeBasisPoints,
			Instant effectiveFrom, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.planId = Objects.requireNonNull(planId, "planId");
		this.rateKoboPerKwh = rateKoboPerKwh;
		this.vatBasisPoints = vatBasisPoints;
		this.platformFeeBasisPoints = platformFeeBasisPoints;
		this.effectiveFrom = effectiveFrom;
		this.createdAt = createdAt;
	}

	/**
	 * Times are cut to microseconds, the precision Postgres stores, so the response shows exactly what was saved.
	 *
	 * @param effectiveFrom when the price starts, or null for now
	 * @throws IllegalArgumentException if a value is out of range or effectiveFrom is in the past: a backdated
	 * version would claim a price applied at a time when tenants were charged something else
	 */
	public static TariffVersion create(UUID planId, long rateKoboPerKwh, int vatBasisPoints,
			int platformFeeBasisPoints, Instant effectiveFrom, Instant now) {
		if (rateKoboPerKwh <= 0) {
			throw new IllegalArgumentException("Rate must be greater than zero");
		}
		if (vatBasisPoints < 0 || vatBasisPoints > MAX_VAT_BASIS_POINTS) {
			throw new IllegalArgumentException("VAT must be between 0 and " + MAX_VAT_BASIS_POINTS + " basis points");
		}
		if (platformFeeBasisPoints < 0 || platformFeeBasisPoints > MAX_PLATFORM_FEE_BASIS_POINTS) {
			throw new IllegalArgumentException(
					"Platform fee must be between 0 and " + MAX_PLATFORM_FEE_BASIS_POINTS + " basis points");
		}
		Instant created = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
		Instant start = effectiveFrom == null ? created : effectiveFrom.truncatedTo(ChronoUnit.MICROS);
		if (start.isBefore(created)) {
			throw new IllegalArgumentException("Effective date cannot be in the past");
		}
		return new TariffVersion(planId, rateKoboPerKwh, vatBasisPoints, platformFeeBasisPoints, start, created);
	}

	public UUID getId() {
		return id;
	}

	public UUID getPlanId() {
		return planId;
	}

	public long getRateKoboPerKwh() {
		return rateKoboPerKwh;
	}

	public int getVatBasisPoints() {
		return vatBasisPoints;
	}

	public int getPlatformFeeBasisPoints() {
		return platformFeeBasisPoints;
	}

	public Instant getEffectiveFrom() {
		return effectiveFrom;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof TariffVersion other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "TariffVersion[id=" + id + ", planId=" + planId + ", effectiveFrom=" + effectiveFrom + "]";
	}

}
