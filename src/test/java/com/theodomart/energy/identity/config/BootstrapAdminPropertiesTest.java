package com.theodomart.energy.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BootstrapAdminPropertiesTest {

	private static final String PASSWORD = "admin-secret-123";

	@Test
	void nothingSetMeansNoBootstrap() {
		assertThat(new BootstrapAdminProperties(null, null, null).isConfigured()).isFalse();
		assertThat(new BootstrapAdminProperties("", " ", "").isConfigured()).isFalse();
	}

	@Test
	void allThreeSetMeansBootstrap() {
		BootstrapAdminProperties properties = new BootstrapAdminProperties(" admin@example.com ", "08031234567",
				PASSWORD);

		assertThat(properties.isConfigured()).isTrue();
		assertThat(properties.email()).isEqualTo("admin@example.com");
		assertThat(properties.phone()).isEqualTo("08031234567");
		assertThat(properties.password()).isEqualTo(PASSWORD);
	}

	@ParameterizedTest
	@CsvSource(nullValues = "-", value = { "admin@example.com, -, -, 'BOOTSTRAP_ADMIN_PHONE, BOOTSTRAP_ADMIN_PASSWORD'",
			"-, 08031234567, -, 'BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PASSWORD'",
			"-, -, admin-secret-123, 'BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PHONE'",
			"admin@example.com, 08031234567, -, BOOTSTRAP_ADMIN_PASSWORD",
			"-, 08031234567, admin-secret-123, BOOTSTRAP_ADMIN_EMAIL" })
	void halfConfiguredStopsStartupAndNamesWhatIsMissing(String email, String phone, String password,
			String missing) {
		assertThatThrownBy(() -> new BootstrapAdminProperties(email, phone, password))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(missing)
			.message()
			.doesNotContain(PASSWORD);
	}

	@ParameterizedTest
	@ValueSource(strings = { "admin", "admin@", "@example.com", "ad min@example.com", "admin@example" })
	void anEmailThatIsNotOneStopsStartup(String email) {
		assertThatThrownBy(() -> new BootstrapAdminProperties(email, "08031234567", PASSWORD))
			.hasMessageContaining("BOOTSTRAP_ADMIN_EMAIL");
	}

	@Test
	void thePasswordFollowsTheRegistrationRules() {
		assertThatThrownBy(() -> new BootstrapAdminProperties("admin@example.com", "08031234567", "too-short"))
			.hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD")
			.message()
			.doesNotContain("too-short");
		// 25 characters, but 75 bytes in UTF-8: BCrypt would silently ignore the end of it.
		String tooManyBytes = "€".repeat(25);
		assertThatThrownBy(() -> new BootstrapAdminProperties("admin@example.com", "08031234567", tooManyBytes))
			.hasMessageContaining("72 bytes");
	}

	@Test
	void printingThePropertiesNeverShowsThePassword() {
		BootstrapAdminProperties properties = new BootstrapAdminProperties("admin@example.com", "08031234567",
				PASSWORD);

		assertThat(properties.toString()).doesNotContain(PASSWORD).contains("[redacted]").contains("admin@example.com");
		assertThat(new BootstrapAdminProperties(null, null, null).toString()).contains("unset");
	}

}
