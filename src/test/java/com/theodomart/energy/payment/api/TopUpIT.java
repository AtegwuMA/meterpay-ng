package com.theodomart.energy.payment.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.payment.domain.PaymentIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The top-up endpoint against a Paystack stand-in on a real port, so the whole path from HTTP to HTTP is real. */
@AutoConfigureMockMvc
class TopUpIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private static final String TOP_UPS = "/api/v1/wallet/top-ups";

	// Started here, not by a JUnit extension, so the port exists before Spring reads it below.
	private static final WireMockServer PAYSTACK = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		PAYSTACK.start();
	}

	@DynamicPropertySource
	static void pointPaystackAtTheStub(DynamicPropertyRegistry registry) {
		registry.add("meterpay.payment.paystack.base-url", PAYSTACK::baseUrl);
		registry.add("meterpay.payment.paystack.read-timeout", () -> "1s");
	}

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private String tenant;

	private String tenantEmail;

	@Autowired
	TopUpIT(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void aTenantAndAWorkingPaystack() throws Exception {
		PAYSTACK.resetAll();
		PAYSTACK.stubFor(post("/transaction/initialize").willReturn(okJson("""
				{"status": true, "data": {"authorization_url": "https://checkout.paystack.com/ok",
				"reference": "{{jsonPath request.body '$.reference'}}"}}
				""").withTransformers("response-template")));
		tenantEmail = "tenant-" + UUID.randomUUID() + "@example.com";
		tenant = tenantToken(tenantEmail);
	}

	@Test
	void aTopUpIsRecordedThenSentToPaystackAndReturnsThePaymentPage() throws Exception {
		String body = topUp(tenant, "key-" + UUID.randomUUID(), 500_000).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
			.andExpect(jsonPath("$.amountKobo").value(500_000))
			.andExpect(jsonPath("$.authorizationUrl").value("https://checkout.paystack.com/ok"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String intentId = JsonPath.read(body, "$.intentId");

		PAYSTACK.verify(postRequestedFor(urlPathEqualTo("/transaction/initialize")).withRequestBody(equalToJson(
				"{\"reference\": \"" + intentId + "\", \"amount\": 500000, \"email\": \"" + tenantEmail + "\"}", true,
				true)));
		assertThat(jdbc.queryForObject("SELECT status FROM payment_intents WHERE id = ?::uuid", String.class, intentId))
			.isEqualTo("PAYMENT_PENDING");
		assertThat(jdbc.queryForObject("SELECT gateway_reference FROM payment_intents WHERE id = ?::uuid", String.class,
				intentId))
			.isEqualTo(intentId);
	}

	@Test
	void aRetryWithTheSameKeyGetsTheSameIntentAndPaystackIsCalledOnce() throws Exception {
		String key = "key-" + UUID.randomUUID();
		String first = intentIdOf(topUp(tenant, key, 500_000).andExpect(status().isCreated()));

		topUp(tenant, key, 500_000).andExpect(status().isOk())
			.andExpect(jsonPath("$.intentId").value(first))
			.andExpect(jsonPath("$.authorizationUrl").value("https://checkout.paystack.com/ok"));

		PAYSTACK.verify(1, postRequestedFor(urlPathEqualTo("/transaction/initialize")));
	}

	@Test
	void theSameKeyForADifferentAmountIsRefused() throws Exception {
		String key = "key-" + UUID.randomUUID();
		topUp(tenant, key, 500_000).andExpect(status().isCreated());

		topUp(tenant, key, 900_000).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		PAYSTACK.verify(1, postRequestedFor(urlPathEqualTo("/transaction/initialize")));
	}

	@Test
	void twoTenantsMayUseTheSameKeyWithoutMeetingEachOther() throws Exception {
		String key = "shared-key-" + UUID.randomUUID();
		String other = tenantToken("tenant-" + UUID.randomUUID() + "@example.com");

		String mine = intentIdOf(topUp(tenant, key, 500_000).andExpect(status().isCreated()));
		String theirs = intentIdOf(topUp(other, key, 900_000).andExpect(status().isCreated()));

		assertThat(theirs).isNotEqualTo(mine);
	}

	@Test
	void concurrentRetriesMakeOneIntentAndOnePaystackCall() throws Exception {
		String key = "key-" + UUID.randomUUID();
		int threads = 6;
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<Integer>> attempts = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			attempts.add(() -> {
				start.await();
				return topUp(tenant, key, 500_000).andReturn().getResponse().getStatus();
			});
		}
		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> attempt : attempts) {
				futures.add(pool.submit(attempt));
			}
			start.countDown();
			for (Future<Integer> future : futures) {
				statuses.add(future.get());
			}
		}

		assertThat(statuses).containsOnlyOnce(201).containsOnly(201, 200);
		assertThat(intentsWithKeyOf(key)).isOne();
		PAYSTACK.verify(1, postRequestedFor(urlPathEqualTo("/transaction/initialize")));
	}

	@Test
	void aPaystackOutageKeepsTheIntentForReconciliation() throws Exception {
		PAYSTACK.stubFor(post("/transaction/initialize").willReturn(aResponse().withStatus(503)));
		String key = "key-" + UUID.randomUUID();

		topUp(tenant, key, 500_000).andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_ERROR"));

		// Committed before the call, and left INITIATED: Paystack may or may not have it.
		assertThat(statusOfKey(key)).isEqualTo("INITIATED");
	}

	@Test
	void aTimeoutKeepsTheIntentForReconciliationToo() throws Exception {
		PAYSTACK.stubFor(post("/transaction/initialize").willReturn(okJson("{}").withFixedDelay(3_000)));
		String key = "key-" + UUID.randomUUID();

		topUp(tenant, key, 500_000).andExpect(status().isBadGateway());

		assertThat(statusOfKey(key)).isEqualTo("INITIATED");
	}

	@Test
	void aRefusalFromPaystackFailsTheIntentAndARetryReportsIt() throws Exception {
		PAYSTACK.stubFor(post("/transaction/initialize").willReturn(aResponse().withStatus(400)));
		String key = "key-" + UUID.randomUUID();

		topUp(tenant, key, 500_000).andExpect(status().isBadGateway());
		topUp(tenant, key, 500_000).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.authorizationUrl").doesNotExist());

		assertThat(jdbc.queryForObject(
				"SELECT failure_reason FROM payment_intents WHERE tenant_id = ? AND status = 'FAILED'", String.class,
				tenantId()))
			.contains("400");
	}

	@Test
	void anIdempotencyKeyIsRequired() throws Exception {
		mvc.perform(request(TOP_UPS)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amountKobo\": 500000}")).andExpect(status().isBadRequest());
		topUp(tenant, "   ", 500_000).andExpect(status().isBadRequest());
		topUp(tenant, "k".repeat(256), 500_000).andExpect(status().isBadRequest());
		PAYSTACK.verify(0, postRequestedFor(urlPathEqualTo("/transaction/initialize")));
	}

	@Test
	void theAmountMustBeWithinLimits() throws Exception {
		topUp(tenant, "key-" + UUID.randomUUID(), TopUpController.MIN_KOBO - 1).andExpect(status().isBadRequest());
		topUp(tenant, "key-" + UUID.randomUUID(), TopUpController.MAX_KOBO + 1).andExpect(status().isBadRequest());
		topUp(tenant, "key-" + UUID.randomUUID(), TopUpController.MIN_KOBO).andExpect(status().isCreated());
	}

	@Test
	void onlyTenantsCanTopUpAndOnlyWhenLoggedIn() throws Exception {
		String landlord = landlordToken();

		topUp(landlord, "key-" + UUID.randomUUID(), 500_000).andExpect(status().isForbidden());
		mvc.perform(request(TOP_UPS)
			.header("Idempotency-Key", "key")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amountKobo\": 500000}")).andExpect(status().isUnauthorized());
		PAYSTACK.verify(0, postRequestedFor(urlPathEqualTo("/transaction/initialize")));
	}

	@Test
	void checkingAPendingTopUpAsksPaystackAndCreditsAPaidOne() throws Exception {
		String intentId = intentIdOf(topUp(tenant, "key-" + UUID.randomUUID(), 500_000));
		paystackVerifies(intentId, okJson("""
				{"status": true, "data": {"status": "success", "reference": "%s", "amount": 500000,
				"currency": "NGN", "paid_at": "2026-11-19T10:15:30.000Z", "gateway_response": "Approved"}}
				""".formatted(intentId)));

		check(tenant, intentId).andExpect(status().isOk())
			.andExpect(jsonPath("$.intentId").value(intentId))
			.andExpect(jsonPath("$.status").value("PAID"));
		// Settled now, so a second look is answered from the database.
		check(tenant, intentId).andExpect(jsonPath("$.status").value("PAID"));

		assertThat(walletBalance(tenant)).isEqualTo(500_000);
		PAYSTACK.verify(1, getRequestedFor(urlPathEqualTo("/transaction/verify/" + intentId)));
	}

	@Test
	void checkingWhilePaystackIsDownStillAnswersWithTheStoredStatus() throws Exception {
		String intentId = intentIdOf(topUp(tenant, "key-" + UUID.randomUUID(), 500_000));
		paystackVerifies(intentId, aResponse().withStatus(503));

		check(tenant, intentId).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
			.andExpect(jsonPath("$.authorizationUrl").value("https://checkout.paystack.com/ok"));
	}

	@Test
	void onlyTheOwnerCanSeeATopUp() throws Exception {
		String intentId = intentIdOf(topUp(tenant, "key-" + UUID.randomUUID(), 500_000));
		String other = tenantToken("tenant-" + UUID.randomUUID() + "@example.com");

		check(other, intentId).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		check(tenant, UUID.randomUUID().toString()).andExpect(status().isNotFound());
		check(landlordToken(), intentId).andExpect(status().isForbidden());
		PAYSTACK.verify(0, getRequestedFor(urlPathEqualTo("/transaction/verify/" + intentId)));
	}

	private void paystackVerifies(String intentId, ResponseDefinitionBuilder response) {
		PAYSTACK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/transaction/verify/" + intentId)
			.willReturn(response));
	}

	private ResultActions check(String token, String intentId) throws Exception {
		return mvc.perform(get(TOP_UPS + "/" + intentId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private long walletBalance(String token) throws Exception {
		Number balance = JsonPath.read(mvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + token)).andReturn().getResponse().getContentAsString(), "$.balanceKobo");
		return balance.longValue();
	}

	private ResultActions topUp(String token, String key, long kobo) throws Exception {
		return mvc.perform(request(TOP_UPS)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amountKobo\": " + kobo + "}"));
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String path) {
		return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
	}

	private String statusOfKey(String key) throws Exception {
		return jdbc.queryForObject("SELECT status FROM payment_intents WHERE idempotency_key = ?", String.class,
				PaymentIntent.idempotencyKeyFor(tenantId(), key));
	}

	private int intentsWithKeyOf(String key) throws Exception {
		return jdbc.queryForObject("SELECT count(*) FROM payment_intents WHERE idempotency_key = ?", Integer.class,
				PaymentIntent.idempotencyKeyFor(tenantId(), key));
	}

	private UUID tenantId() throws Exception {
		return UUID.fromString(JsonPath.read(mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + tenant)).andReturn().getResponse().getContentAsString(), "$.id"));
	}

	private static String intentIdOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.intentId");
	}

	private String tenantToken(String email) throws Exception {
		String landlord = landlordToken();
		String building = JsonPath.read(mvc.perform(request("/api/v1/buildings")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + landlord)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "Sunrise Court", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
					""")).andReturn().getResponse().getContentAsString(), "$.id");
		String unit = JsonPath.read(mvc.perform(request("/api/v1/buildings/" + building + "/units")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + landlord)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"label\": \"Flat 1\"}")).andReturn().getResponse().getContentAsString(), "$.id");
		String code = JsonPath.read(mvc.perform(request("/api/v1/units/" + unit + "/invites")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + landlord)).andReturn().getResponse().getContentAsString(),
				"$.code");
		register(email, code);
		return login(email);
	}

	private String landlordToken() throws Exception {
		String email = "landlord-" + UUID.randomUUID() + "@example.com";
		register(email, null);
		return login(email);
	}

	private void register(String email, String inviteCode) throws Exception {
		String code = inviteCode == null ? "" : ", \"inviteCode\": \"" + inviteCode + "\"";
		mvc.perform(request("/api/v1/auth/register")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Tolu Ade"%s}
					""".formatted(email, "+234806" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000), PASSWORD,
					code)))
			.andExpect(status().isCreated());
	}

	private String login(String email) throws Exception {
		String body = mvc.perform(request("/api/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, PASSWORD)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

}
