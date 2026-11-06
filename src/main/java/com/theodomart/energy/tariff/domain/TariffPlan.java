package com.theodomart.energy.tariff.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read only here: rows are created by TariffPlanRepository.insertIfAbsent, so two first requests cannot collide. */
@Entity
@Table(name = "tariff_plans")
public class TariffPlan {

	/** Used when the landlord does not name the plan on the building's first version. */
	public static final String DEFAULT_NAME = "Standard";

	@Id
	private UUID id;

	@Column(name = "building_id", nullable = false, updatable = false)
	private UUID buildingId;

	@Column(nullable = false, updatable = false)
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected TariffPlan() {
		// for JPA
	}

	public static String normaliseName(String name) {
		return name == null || name.isBlank() ? DEFAULT_NAME : name.strip();
	}

	public UUID getId() {
		return id;
	}

	public UUID getBuildingId() {
		return buildingId;
	}

	public String getName() {
		return name;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof TariffPlan other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "TariffPlan[id=" + id + ", buildingId=" + buildingId + "]";
	}

}
