package com.theodomart.energy.identity.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// The full app with the real security chain, database and token handling. Each test uses its own email
// and phone, so tests never depend on each other or on data other ITs leave in the shared database.
@AutoConfigureMockMvc
class AuthFlowIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	@Autowired
	AuthFlowIT(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@Test
	void registerThenLoginThenReadOwnProfile() throws Exception {
		String email = uniqueEmail();

		register(email, "0803 " + uniqueSevenDigits(), PASSWORD).andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/me"))
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.phone").value(containsString("+234803")))
			.andExpect(jsonPath("$.role").value("LANDLORD"))
			.andExpect(jsonPath("$.password").doesNotExist())
			.andExpect(jsonPath("$.passwordHash").doesNotExist());

		String token = loginForToken(email, PASSWORD);

		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.fullName").value("Ada Obi"))
			.andExpect(jsonPath("$.role").value("LANDLORD"));
	}

	@Test
	void loginReturnsBearerTokenValidForFifteenMinutes() throws Exception {
		String email = uniqueEmail();
		register(email, uniquePhone(), PASSWORD).andExpect(status().isCreated());

		login(email.toUpperCase(), PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void wrongPasswordGives401ProblemDetail() throws Exception {
		String email = uniqueEmail();
		register(email, uniquePhone(), PASSWORD).andExpect(status().isCreated());

		login(email, "wrong-password-123").andExpect(status().isUnauthorized())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.detail").value("Invalid email or password."))
			.andExpect(jsonPath("$.traceId").isNotEmpty())
			.andExpect(jsonPath("$.accessToken").doesNotExist());
	}

	@Test
	void unknownEmailGetsExactlyTheSameAnswerAsWrongPassword() throws Exception {
		login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.detail").value("Invalid email or password."));
	}

	@Test
	void disabledAccountCannotLogInOrUseAnExistingToken() throws Exception {
		String email = uniqueEmail();
		register(email, uniquePhone(), PASSWORD).andExpect(status().isCreated());
		String token = loginForToken(email, PASSWORD);

		jdbc.update("UPDATE users SET status = 'DISABLED' WHERE email = ?", email);

		login(email, PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("Invalid email or password."));
		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void meWithoutTokenGives401ProblemDetailWithBearerChallenge() throws Exception {
		mvc.perform(get("/api/v1/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.instance").value("/api/v1/me"))
			.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void malformedOrTamperedTokenGives401ProblemDetail() throws Exception {
		String email = uniqueEmail();
		register(email, uniquePhone(), PASSWORD).andExpect(status().isCreated());
		String token = loginForToken(email, PASSWORD);
		String tampered = token.substring(0, token.length() - 4) + "AAAA";

		for (String bad : new String[] { "not-a-jwt", tampered }) {
			mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bad))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
				.andExpect(content().contentType(PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}
	}

	@Test
	void duplicateEmailGives409EvenWithDifferentCase() throws Exception {
		String email = uniqueEmail();
		register(email, uniquePhone(), PASSWORD).andExpect(status().isCreated());

		register(email.toUpperCase(), uniquePhone(), PASSWORD).andExpect(status().isConflict())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT"))
			.andExpect(jsonPath("$.detail").value("An account with this email already exists."));
	}

	@Test
	void duplicatePhoneInAnotherFormatGives409() throws Exception {
		String digits = uniqueSevenDigits();
		register(uniqueEmail(), "0803" + digits, PASSWORD).andExpect(status().isCreated());

		register(uniqueEmail(), "+234803" + digits, PASSWORD).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT"))
			.andExpect(jsonPath("$.detail").value("An account with this phone number already exists."));
	}

	@Test
	void invalidRegistrationGives400WithFieldErrors() throws Exception {
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "not-an-email", "phone": "", "password": "short", "fullName": ""}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(4))
			.andExpect(content().string(not(containsString("short"))));
	}

	@Test
	void unrecognisedPhoneFormatGives400() throws Exception {
		register(uniqueEmail(), "12345", PASSWORD).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.detail").value(containsString("Phone number")));
	}

	@Test
	void passwordLongerThan72BytesIsRefusedInsteadOfSilentlyTruncated() throws Exception {
		// 36 characters but 72+ bytes: each "é" is 2 bytes in UTF-8, so this passes @Size and fails the byte check.
		String password = "é".repeat(36) + "x";

		register(uniqueEmail(), uniquePhone(), password).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.detail").value("Password must be at most 72 bytes."));
	}

	private ResultActions register(String email, String phone, String password) throws Exception {
		return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Ada Obi"}
				""".formatted(email, phone, password)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s"}
				""".formatted(email, password)));
	}

	private String loginForToken(String email, String password) throws Exception {
		String body = login(email, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String uniqueEmail() {
		return "landlord-" + UUID.randomUUID() + "@example.com";
	}

	private static String uniquePhone() {
		return "+234803" + uniqueSevenDigits();
	}

	private static String uniqueSevenDigits() {
		return String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000));
	}

}
