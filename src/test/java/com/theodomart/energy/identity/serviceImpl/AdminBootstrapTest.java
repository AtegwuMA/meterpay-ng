package com.theodomart.energy.identity.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.theodomart.energy.common.domain.Role;
import com.theodomart.energy.identity.config.BootstrapAdminProperties;
import com.theodomart.energy.identity.domain.User;
import com.theodomart.energy.identity.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapTest {

	private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");

	private static final String PASSWORD = "admin-secret-123";

	private final UserRepository users = mock();

	private final PasswordEncoder passwordEncoder = mock();

	private final PlatformTransactionManager transactionManager = mock();

	@Test
	void withNothingConfiguredItDoesNothing() {
		bootstrap(new BootstrapAdminProperties(null, null, null)).run(null);

		verifyNoInteractions(users, passwordEncoder);
	}

	@Test
	void anExistingAdminIsLeftAloneAndNoPasswordIsHashed() {
		when(users.existsByRole(Role.ADMIN)).thenReturn(true);

		configured().run(null);

		verify(users, never()).saveAndFlush(any());
		verifyNoInteractions(passwordEncoder);
	}

	@Test
	void theFirstAdminIsCreatedWithAHashedPasswordAndNothingElse(CapturedOutput output) {
		when(passwordEncoder.encode(PASSWORD)).thenReturn("$2a$12$hashed");

		configured().run(null);

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(users).saveAndFlush(saved.capture());
		User admin = saved.getValue();
		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(admin.getEmail()).isEqualTo("admin@example.com");
		assertThat(admin.getPhone()).isEqualTo("+2348031234567");
		assertThat(admin.getPasswordHash()).isEqualTo("$2a$12$hashed");
		assertThat(admin.getFullName()).isEqualTo(AdminBootstrap.FULL_NAME);
		assertThat(output).contains("Bootstrap admin created").doesNotContain(PASSWORD);
	}

	@Test
	void aPhoneThatCannotBeReadStopsStartup() {
		AdminBootstrap bootstrap = bootstrap(new BootstrapAdminProperties("admin@example.com", "not-a-phone", PASSWORD));

		assertThatThrownBy(() -> bootstrap.run(null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("BOOTSTRAP_ADMIN_PHONE")
			.message()
			.doesNotContain(PASSWORD);
	}

	@Test
	void anEmailThatBelongsToSomeoneElseStopsStartup() {
		when(passwordEncoder.encode(PASSWORD)).thenReturn("$2a$12$hashed");
		when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_users_email"));
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(
				User.create("admin@example.com", "+2348031234567", "hash", "A Landlord", Role.LANDLORD, NOW)));

		assertThatThrownBy(() -> configured().run(null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("not an admin");
	}

	@Test
	void anotherInstanceCreatingTheAdminFirstIsFine(CapturedOutput output) {
		when(passwordEncoder.encode(PASSWORD)).thenReturn("$2a$12$hashed");
		when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_users_email"));
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(
				User.create("admin@example.com", "+2348031234567", "hash", "Admin", Role.ADMIN, NOW)));

		configured().run(null);

		assertThat(output).contains("another instance created the admin first");
	}

	private AdminBootstrap configured() {
		return bootstrap(new BootstrapAdminProperties("Admin@Example.com", "08031234567", PASSWORD));
	}

	private AdminBootstrap bootstrap(BootstrapAdminProperties properties) {
		return new AdminBootstrap(properties, users, passwordEncoder, transactionManager,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

}
