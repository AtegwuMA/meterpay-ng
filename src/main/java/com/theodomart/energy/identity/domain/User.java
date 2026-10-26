package com.theodomart.energy.identity.domain;

import com.theodomart.energy.common.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "users")
public class User {

	// E.164: "+", country code, subscriber number, 8 to 15 digits in total. Nigerian numbers are
	// +234 followed by 10 digits. Converting local formats (080...) is the API's job, not the entity's.
	private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");

	@Id
	private UUID id;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String phone;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private UserStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	// Wrapper type on purpose: null tells Spring Data the entity is new, so save() persists instead of merging.
	@Version
	private Long version;

	protected User() {
		// for JPA
	}

	private User(UUID id, String email, String phone, String passwordHash, String fullName, Role role, Instant now) {
		this.id = id;
		this.email = email;
		this.phone = phone;
		this.passwordHash = passwordHash;
		this.fullName = fullName;
		this.role = role;
		this.status = UserStatus.ACTIVE;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * A new, active user. The ID is assigned here rather than by the database so equals and hashCode are
	 * stable from the moment the object exists. {@code now} comes from the caller's injected Clock.
	 */
	public static User create(String email, String phone, String passwordHash, String fullName, Role role,
			Instant now) {
		return new User(UUID.randomUUID(), normaliseEmail(email), requireE164(phone), requireText(passwordHash,
				"passwordHash"), requireText(fullName, "fullName").strip(), Objects.requireNonNull(role, "role"),
				Objects.requireNonNull(now, "now"));
	}

	/** Emails are compared case-insensitively, so they are stored in one canonical form. */
	public static String normaliseEmail(String email) {
		return requireText(email, "email").strip().toLowerCase(Locale.ROOT);
	}

	private static String requireE164(String phone) {
		String value = requireText(phone, "phone").strip();
		if (!E164.matcher(value).matches()) {
			throw new IllegalArgumentException("Phone must be in E.164 format, for example +2348031234567");
		}
		return value;
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	public UUID getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPhone() {
		return phone;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getFullName() {
		return fullName;
	}

	public Role getRole() {
		return role;
	}

	public UserStatus getStatus() {
		return status;
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
		// instanceof rather than getClass(): Hibernate may hand out a proxy subclass of User.
		return o instanceof User other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		// No email, phone or password hash: toString ends up in logs.
		return "User[id=" + id + ", role=" + role + ", status=" + status + "]";
	}

}
