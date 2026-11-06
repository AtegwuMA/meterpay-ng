package com.theodomart.energy.tariff.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The VAT rate for the whole platform, from one moment onwards. Read only: new rates are added by migration. */
@Entity
@Table(name = "tax_rates")
public class TaxRate {

	@Id
	private UUID id;

	@Column(name = "vat_basis_points", nullable = false, updatable = false)
	private int vatBasisPoints;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private Instant effectiveFrom;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected TaxRate() {
		// for JPA
	}

	public int getVatBasisPoints() {
		return vatBasisPoints;
	}

	public Instant getEffectiveFrom() {
		return effectiveFrom;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof TaxRate other && Objects.equals(id, other.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "TaxRate[id=" + id + ", vatBasisPoints=" + vatBasisPoints + ", effectiveFrom=" + effectiveFrom + "]";
	}

}
