package com.theodomart.energy.property.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "buildings")
public class Building {

	@Id
	private UUID id;

	// The owning landlord. A plain id, not a relation: users belong to the identity module.
	@Column(name = "landlord_id", nullable = false, updatable = false)
	private UUID landlordId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String address;

	@Column(nullable = false)
	private String state;

	@Column(nullable = false)
	private String lga;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Building() {
		// for JPA
	}

	private Building(UUID landlordId, String name, String address, String state, String lga, Instant now) {
		this.id = UUID.randomUUID();
		this.landlordId = Objects.requireNonNull(landlordId, "landlordId");
		this.name = requireText(name, "name");
		this.address = requireText(address, "address");
		this.state = requireText(state, "state");
		this.lga = requireText(lga, "lga");
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}

	public static Building create(UUID landlordId, String name, String address, String state, String lga,
			Instant now) {
		return new Building(landlordId, name, address, state, lga, now);
	}

	/** Partial update: a null argument leaves that field as it is. A blank one is rejected. */
	public void update(String name, String address, String state, String lga, Instant now) {
		String newName = name == null ? this.name : requireText(name, "name");
		String newAddress = address == null ? this.address : requireText(address, "address");
		String newState = state == null ? this.state : requireText(state, "state");
		String newLga = lga == null ? this.lga : requireText(lga, "lga");
		boolean changed = !newName.equals(this.name) || !newAddress.equals(this.address)
				|| !newState.equals(this.state) || !newLga.equals(this.lga);
		if (changed) {
			this.name = newName;
			this.address = newAddress;
			this.state = newState;
			this.lga = newLga;
			this.updatedAt = Objects.requireNonNull(now, "now");
		}
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.strip();
	}

	public UUID getId() {
		return id;
	}

	public UUID getLandlordId() {
		return landlordId;
	}

	public String getName() {
		return name;
	}

	public String getAddress() {
		return address;
	}

	public String getState() {
		return state;
	}

	public String getLga() {
		return lga;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof Building other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "Building[id=" + id + ", landlordId=" + landlordId + "]";
	}

}
