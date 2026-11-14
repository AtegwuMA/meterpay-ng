package com.theodomart.energy.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.identity.serviceImpl.AdminBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Starts the application with the three variables set, as an operator would. Its own Spring context, because the
 * bootstrap only runs at startup. The phone uses a prefix no other test generates, so it cannot clash.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = { "meterpay.identity.bootstrap-admin.email=Bootstrap-Admin@Example.com",
		"meterpay.identity.bootstrap-admin.phone=08000000001",
		"meterpay.identity.bootstrap-admin.password=bootstrap-secret-123" })
class AdminBootstrapIT extends IntegrationTest {

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final AdminBootstrap bootstrap;

	@Autowired
	AdminBootstrapIT(MockMvc mvc, JdbcTemplate jdbc, AdminBootstrap bootstrap) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.bootstrap = bootstrap;
	}

	@Test
	void startupCreatedTheAdminWhoCanLogIn() throws Exception {
		String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "bootstrap-admin@example.com", "password": "bootstrap-secret-123"}
				""")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.accessToken");

		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("ADMIN"))
			.andExpect(jsonPath("$.phone").value("+2348000000001"));
	}

	@Test
	void runningAgainChangesNothing() {
		bootstrap.run(null);
		bootstrap.run(null);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class)).isOne();
	}

	@Test
	void theAdminHasNoLedgerAccount() {
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ledger_accounts a JOIN users u ON u.id = a.owner_id WHERE u.role = 'ADMIN'
				""", Integer.class)).isZero();
	}

	@Test
	void thePasswordIsStoredOnlyAsABcryptHash() {
		String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE role = 'ADMIN'", String.class);

		assertThat(hash).startsWith("$2").doesNotContain("bootstrap-secret-123");
	}

}
