package com.theodomart.energy.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.identity.domain.OpaqueTokens;
import com.theodomart.energy.identity.service.AuthTokens;
import com.theodomart.energy.identity.service.IdentityService;
import com.theodomart.energy.identity.service.InvalidRefreshTokenException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class RefreshTokenRotationIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final IdentityService identity;

	@Autowired
	RefreshTokenRotationIT(MockMvc mvc, JdbcTemplate jdbc, IdentityService identity) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.identity = identity;
	}

	@Test
	void loginReturnsARefreshTokenValidForThirtyDays() throws Exception {
		String email = registerLandlord();

		login(email).andExpect(status().isOk())
			.andExpect(jsonPath("$.refreshToken").isNotEmpty())
			.andExpect(jsonPath("$.refreshTokenExpiresIn").value(30 * 24 * 3600))
			.andExpect(jsonPath("$.expiresIn").value(900));
	}

	@Test
	void refreshRotatesToANewPairAndTheNewAccessTokenWorks() throws Exception {
		Tokens first = loginForTokens(registerLandlord());

		Tokens second = tokens(refresh(first.refresh()).andExpect(status().isOk()));

		assertThat(second.refresh()).isNotEqualTo(first.refresh());
		assertThat(second.access()).isNotBlank();
		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + second.access()))
			.andExpect(status().isOk());
		assertThat(tokenRow(first.refresh()).replacedBy()).isEqualTo(tokenRow(second.refresh()).id());
		assertThat(tokenRow(first.refresh()).familyId()).isEqualTo(tokenRow(second.refresh()).familyId());
	}

	@Test
	void reusingARotatedTokenRevokesTheWholeFamily() throws Exception {
		Tokens first = loginForTokens(registerLandlord());
		Tokens second = tokens(refresh(first.refresh()).andExpect(status().isOk()));
		Tokens third = tokens(refresh(second.refresh()).andExpect(status().isOk()));

		// An attacker replays the first token, which was already rotated away.
		refresh(first.refresh()).andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.detail").value("Refresh token is invalid or expired."));

		// The newest token, never reused, is now dead too: the whole family was revoked, and committed despite
		// the 401 (the service must not roll the revocation back with the exception).
		refresh(third.refresh()).andExpect(status().isUnauthorized());
		Integer live = jdbc.queryForObject(
				"SELECT count(*) FROM refresh_tokens WHERE family_id = ? AND revoked_at IS NULL", Integer.class,
				tokenRow(first.refresh()).familyId());
		assertThat(live).isZero();
	}

	@Test
	void reuseDoesNotTouchOtherFamiliesOfTheSameUser() throws Exception {
		String email = registerLandlord();
		Tokens phone = loginForTokens(email);
		Tokens laptop = loginForTokens(email);
		refresh(phone.refresh()).andExpect(status().isOk());

		refresh(phone.refresh()).andExpect(status().isUnauthorized());

		refresh(laptop.refresh()).andExpect(status().isOk());
	}

	@Test
	void concurrentUseOfOneTokenLetsAtMostOneRequestThrough() throws Exception {
		Tokens first = loginForTokens(registerLandlord());
		int threads = 8;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<Future<AuthTokens>> results = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return identity.refresh(first.refresh());
				}));
			}
			start.countDown();
			int succeeded = 0;
			int rejected = 0;
			for (Future<AuthTokens> result : results) {
				try {
					result.get(30, TimeUnit.SECONDS);
					succeeded++;
				}
				catch (java.util.concurrent.ExecutionException ex) {
					assertThat(ex.getCause()).isInstanceOf(InvalidRefreshTokenException.class);
					rejected++;
				}
			}
			// The row lock serialises them: one rotates, the rest see a retired token and count as reuse.
			assertThat(succeeded).isEqualTo(1);
			assertThat(rejected).isEqualTo(threads - 1);
		}
		finally {
			pool.shutdownNow();
		}
		Integer live = jdbc.queryForObject(
				"SELECT count(*) FROM refresh_tokens WHERE family_id = ? AND revoked_at IS NULL", Integer.class,
				tokenRow(first.refresh()).familyId());
		assertThat(live).as("reuse revoked the family, including the token the winner received").isZero();
	}

	@Test
	void expiredRefreshTokenIsRejectedWithoutRevokingTheFamily() throws Exception {
		Tokens first = loginForTokens(registerLandlord());
		jdbc.update("UPDATE refresh_tokens SET created_at = now() - interval '31 days', "
				+ "expires_at = now() - interval '1 day' WHERE token_hash = ?", OpaqueTokens.sha256Hex(first.refresh()));

		refresh(first.refresh()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("Refresh token is invalid or expired."));
		assertThat(tokenRow(first.refresh()).revoked()).isFalse();
	}

	@Test
	void unknownRefreshTokenIsRejected() throws Exception {
		refresh(OpaqueTokens.generate()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void disabledUserCannotRefreshAndTheirFamilyIsRevoked() throws Exception {
		String email = registerLandlord();
		Tokens first = loginForTokens(email);
		jdbc.update("UPDATE users SET status = 'DISABLED' WHERE email = ?", email);

		refresh(first.refresh()).andExpect(status().isUnauthorized());

		assertThat(tokenRow(first.refresh()).revoked()).isTrue();
	}

	@Test
	void logoutRevokesTheSessionSoItsRefreshTokenStopsWorking() throws Exception {
		Tokens first = loginForTokens(registerLandlord());

		logout(first.access(), first.refresh()).andExpect(status().isNoContent());

		refresh(first.refresh()).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutWithSomeoneElsesRefreshTokenDoesNothingAndSaysNothing() throws Exception {
		Tokens victim = loginForTokens(registerLandlord());
		Tokens attacker = loginForTokens(registerLandlord());

		logout(attacker.access(), victim.refresh()).andExpect(status().isNoContent());

		refresh(victim.refresh()).andExpect(status().isOk());
	}

	@Test
	void logoutRequiresAnAccessToken() throws Exception {
		Tokens first = loginForTokens(registerLandlord());

		mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"" + first.refresh() + "\"}")).andExpect(status().isUnauthorized());

		refresh(first.refresh()).andExpect(status().isOk());
	}

	@Test
	void refreshTokensAreStoredOnlyAsHashes() throws Exception {
		Tokens first = loginForTokens(registerLandlord());

		Integer plain = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
				Integer.class, first.refresh());
		assertThat(plain).isZero();
		assertThat(tokenRow(first.refresh()).id()).isNotNull();
	}

	private String registerLandlord() throws Exception {
		String email = "landlord-" + UUID.randomUUID() + "@example.com";
		String phone = "+234803" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Ada Obi"}
				""".formatted(email, phone, PASSWORD))).andExpect(status().isCreated());
		return email;
	}

	private ResultActions login(String email) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s"}
				""".formatted(email, PASSWORD)));
	}

	private Tokens loginForTokens(String email) throws Exception {
		return tokens(login(email).andExpect(status().isOk()));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"" + refreshToken + "\"}"));
	}

	private ResultActions logout(String accessToken, String refreshToken) throws Exception {
		return mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"" + refreshToken + "\"}"));
	}

	private static Tokens tokens(ResultActions response) throws Exception {
		String body = response.andReturn().getResponse().getContentAsString();
		return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
	}

	private TokenRow tokenRow(String rawRefreshToken) {
		return jdbc.queryForObject(
				"SELECT id, family_id, replaced_by, revoked_at IS NOT NULL AS revoked FROM refresh_tokens WHERE token_hash = ?",
				(rs, n) -> new TokenRow(rs.getObject("id", UUID.class), rs.getObject("family_id", UUID.class),
						rs.getObject("replaced_by", UUID.class), rs.getBoolean("revoked")),
				OpaqueTokens.sha256Hex(rawRefreshToken));
	}

	private record Tokens(String access, String refresh) {
	}

	private record TokenRow(UUID id, UUID familyId, UUID replacedBy, boolean revoked) {
	}

}
