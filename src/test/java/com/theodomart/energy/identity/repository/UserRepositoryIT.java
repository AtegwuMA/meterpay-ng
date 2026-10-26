package com.theodomart.energy.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.common.domain.Role;
import com.theodomart.energy.identity.domain.User;
import com.theodomart.energy.identity.domain.UserStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// Every test rolls back, and uses unique emails and phones, so tests sharing the singleton database
// cannot collide with each other or with data left by other ITs.
@Transactional
class UserRepositoryIT extends IntegrationTest {

	// Microsecond precision: Postgres TIMESTAMPTZ stores micros, so the value survives the round trip exactly.
	private static final Instant NOW = Instant.parse("2026-10-25T09:15:30.123456Z");

	private static final String HASH = "$2a$12$abcdefghijklmnopqrstuuJ9YbWb1o2Ev9xR3m1Qk8yQ7nO5Q6e2a";

	private final UserRepository users;

	private final EntityManager entityManager;

	private final JdbcTemplate jdbc;

	@Autowired
	UserRepositoryIT(UserRepository users, EntityManager entityManager, JdbcTemplate jdbc) {
		this.users = users;
		this.entityManager = entityManager;
		this.jdbc = jdbc;
	}

	@Test
	void savedUserReadsBackFromTheDatabase() {
		User saved = users.saveAndFlush(newUser(uniqueEmail(), uniquePhone()));
		entityManager.clear();

		User loaded = users.findById(saved.getId()).orElseThrow();

		assertThat(loaded).isNotSameAs(saved).isEqualTo(saved);
		assertThat(loaded.getEmail()).isEqualTo(saved.getEmail());
		assertThat(loaded.getPhone()).isEqualTo(saved.getPhone());
		assertThat(loaded.getPasswordHash()).isEqualTo(HASH);
		assertThat(loaded.getRole()).isEqualTo(Role.LANDLORD);
		assertThat(loaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
		assertThat(loaded.getUpdatedAt()).isEqualTo(NOW);
		assertThat(loaded.getVersion()).isZero();
	}

	@Test
	void findsUserByNormalisedEmail() {
		String email = uniqueEmail();
		User saved = users.saveAndFlush(newUser(email.toUpperCase(), uniquePhone()));

		assertThat(users.findByEmail(User.normaliseEmail(email.toUpperCase()))).contains(saved);
		assertThat(users.findByEmail("nobody-" + email)).isEmpty();
	}

	@Test
	void duplicateEmailIsRejected() {
		String email = uniqueEmail();
		users.saveAndFlush(newUser(email, uniquePhone()));

		assertThatThrownBy(() -> users.saveAndFlush(newUser(email, uniquePhone())))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(e -> assertThat(rootMessage(e)).contains("uk_users_email"));
	}

	@Test
	void duplicateEmailDifferingOnlyInCaseIsRejected() {
		String email = uniqueEmail();
		users.saveAndFlush(newUser(email, uniquePhone()));

		assertThatThrownBy(() -> users.saveAndFlush(newUser(email.toUpperCase(), uniquePhone())))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(e -> assertThat(rootMessage(e)).contains("uk_users_email"));
	}

	@Test
	void duplicatePhoneIsRejected() {
		String phone = uniquePhone();
		users.saveAndFlush(newUser(uniqueEmail(), phone));

		assertThatThrownBy(() -> users.saveAndFlush(newUser(uniqueEmail(), phone)))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(e -> assertThat(rootMessage(e)).contains("uk_users_phone"));
	}

	@Test
	void databaseRejectsMixedCaseEmailWrittenAroundTheEntity() {
		assertThatThrownBy(() -> insertRaw("Mixed.Case-" + UUID.randomUUID() + "@example.com", uniquePhone()))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(e -> assertThat(rootMessage(e)).contains("ck_users_email_lowercase"));
	}

	@Test
	void databaseRejectsNonE164PhoneWrittenAroundTheEntity() {
		assertThatThrownBy(() -> insertRaw(uniqueEmail(), "08031234567"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(e -> assertThat(rootMessage(e)).contains("ck_users_phone_e164"));
	}

	private static User newUser(String email, String phone) {
		return User.create(email, phone, HASH, "Ada Obi", Role.LANDLORD, NOW);
	}

	private void insertRaw(String email, String phone) {
		jdbc.update("""
				INSERT INTO users (id, email, phone, password_hash, full_name, role, status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, 'Raw Insert', 'TENANT', 'ACTIVE', now(), now(), 0)
				""", UUID.randomUUID(), email, phone, HASH);
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private static String uniquePhone() {
		return "+23480" + ThreadLocalRandom.current().nextLong(10_000_000L, 100_000_000L);
	}

	private static String rootMessage(Throwable e) {
		return NestedExceptionUtils.getMostSpecificCause(e).getMessage();
	}

}
