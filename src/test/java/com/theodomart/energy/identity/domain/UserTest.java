package com.theodomart.energy.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserTest {

	private static final Instant NOW = Instant.parse("2026-10-25T09:15:30Z");

	private static final String HASH = "$2a$12$abcdefghijklmnopqrstuuJ9YbWb1o2Ev9xR3m1Qk8yQ7nO5Q6e2a";

	@Test
	void newUserIsActiveWithAssignedIdAndTimestamps() {
		User user = User.create("ada@example.com", "+2348031234567", HASH, "Ada Obi", Role.LANDLORD, NOW);

		assertThat(user.getId()).isNotNull();
		assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(user.getRole()).isEqualTo(Role.LANDLORD);
		assertThat(user.getCreatedAt()).isEqualTo(NOW);
		assertThat(user.getUpdatedAt()).isEqualTo(NOW);
		assertThat(user.getVersion()).as("null version marks the entity as new for Spring Data").isNull();
	}

	@Test
	void emailIsStoredTrimmedAndLowerCase() {
		User user = User.create("  Ada.Obi@Example.COM ", "+2348031234567", HASH, "Ada Obi", Role.TENANT, NOW);

		assertThat(user.getEmail()).isEqualTo("ada.obi@example.com");
		assertThat(User.normaliseEmail("Ada.Obi@Example.COM")).isEqualTo("ada.obi@example.com");
	}

	@Test
	void fullNameAndPhoneAreTrimmed() {
		User user = User.create("ada@example.com", " +2348031234567 ", HASH, "  Ada Obi  ", Role.TENANT, NOW);

		assertThat(user.getPhone()).isEqualTo("+2348031234567");
		assertThat(user.getFullName()).isEqualTo("Ada Obi");
	}

	@ParameterizedTest
	@ValueSource(strings = { "08031234567", "2348031234567", "+0348031234567", "+234 803 123 4567", "+1234567",
			"+1234567890123456" })
	void phoneMustBeE164(String phone) {
		assertThatThrownBy(() -> User.create("ada@example.com", phone, HASH, "Ada Obi", Role.TENANT, NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("E.164");
	}

	@Test
	void requiredFieldsMustBePresent() {
		assertThatThrownBy(() -> User.create(" ", "+2348031234567", HASH, "Ada", Role.TENANT, NOW))
			.hasMessageContaining("email");
		assertThatThrownBy(() -> User.create("a@b.com", "+2348031234567", "", "Ada", Role.TENANT, NOW))
			.hasMessageContaining("passwordHash");
		assertThatThrownBy(() -> User.create("a@b.com", "+2348031234567", HASH, null, Role.TENANT, NOW))
			.hasMessageContaining("fullName");
		assertThatThrownBy(() -> User.create("a@b.com", "+2348031234567", HASH, "Ada", null, NOW))
			.hasMessageContaining("role");
		assertThatThrownBy(() -> User.create("a@b.com", "+2348031234567", HASH, "Ada", Role.TENANT, null))
			.hasMessageContaining("now");
	}

	@Test
	void equalityIsByIdOnly() {
		User ada = User.create("ada@example.com", "+2348031234567", HASH, "Ada Obi", Role.TENANT, NOW);
		User twin = User.create("ada@example.com", "+2348031234567", HASH, "Ada Obi", Role.TENANT, NOW);

		assertThat(ada).isEqualTo(ada).hasSameHashCodeAs(ada);
		assertThat(ada).isNotEqualTo(twin).isNotEqualTo(null).isNotEqualTo("ada");
	}

	@Test
	void toStringLeaksNoPersonalData() {
		User user = User.create("ada@example.com", "+2348031234567", HASH, "Ada Obi", Role.TENANT, NOW);

		assertThat(user.toString()).doesNotContain("ada@example.com", "+2348031234567", HASH, "Ada Obi");
	}

}
