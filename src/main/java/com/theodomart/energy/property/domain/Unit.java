package com.theodomart.energy.property.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A flat or room inside a building, for example "Flat 3B". */
@Entity
@Table(name = "units")
public class Unit {

	@Id
	private UUID id;

	@Column(name = "building_id", nullable = false, updatable = false)
	private UUID buildingId;

	@Column(nullable = false)
	private String label;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Unit() {
		// for JPA
	}

	private Unit(UUID buildingId, String label, Instant now) {
		this.id = UUID.randomUUID();
		this.buildingId = Objects.requireNonNull(buildingId, "buildingId");
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}
		// Inner runs of spaces collapsed too, so "Flat  3B" cannot sit beside "Flat 3B".
		this.label = label.strip().replaceAll("\\s+", " ");
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}

	public static Unit create(UUID buildingId, String label, Instant now) {
		return new Unit(buildingId, label, now);
	}

	public UUID getId() {
		return id;
	}

	public UUID getBuildingId() {
		return buildingId;
	}

	public String getLabel() {
		return label;
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
		return o instanceof Unit other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "Unit[id=" + id + ", buildingId=" + buildingId + "]";
	}

}
