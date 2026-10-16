package com.theodomart.energy;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

class FlywayBaselineIT extends IntegrationTest {

	private final Flyway flyway;

	private final JdbcTemplate jdbc;

	private final Environment environment;

	@Autowired
	FlywayBaselineIT(Flyway flyway, JdbcTemplate jdbc, Environment environment) {
		this.flyway = flyway;
		this.jdbc = jdbc;
		this.environment = environment;
	}

	@Test
	void baselineMigrationIsApplied() {
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
		assertThat(flyway.info().pending()).isEmpty();
	}

	@Test
	void pgcryptoExtensionIsInstalled() {
		String digest = jdbc.queryForObject("SELECT encode(digest('meterpay', 'sha256'), 'hex')", String.class);

		assertThat(digest).hasSize(64);
	}

	@Test
	void hibernateValidatesSchemaInsteadOfGeneratingIt() {
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
	}

}
