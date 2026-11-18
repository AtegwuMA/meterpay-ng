package com.theodomart.energy.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.payment.serviceImpl.TopUpReconciliationJob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** The reconciliation job against the real schema and a Paystack stand-in, called directly instead of waiting. */
@AutoConfigureMockMvc
class TopUpReconciliationIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private static final long AMOUNT = 500_000;

	private static final WireMockServer PAYSTACK = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		PAYSTACK.start();
	}

	@DynamicPropertySource
	static void pointPaystackAtTheStub(DynamicPropertyRegistry registry) {
		registry.add("meterpay.payment.paystack.base-url", PAYSTACK::baseUrl);
	}

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final DataSource dataSource;

	private final TopUpReconciliationJob job;

	private String tenant;

	@Autowired
	TopUpReconciliationIT(MockMvc mvc, JdbcTemplate jdbc, DataSource dataSource, TopUpReconciliationJob job) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.dataSource = dataSource;
		this.job = job;
	}

	@BeforeEach
	void aTenantAndAWorkingPaystack() throws Exception {
		PAYSTACK.resetAll();
		PAYSTACK.stubFor(post("/transaction/initialize").willReturn(okJson("""
				{"status": true, "data": {"authorization_url": "https://checkout.paystack.com/ok",
				"reference": "{{jsonPath request.body '$.reference'}}"}}
				""").withTransformers("response-template")));
		tenant = tenantToken();
	}

	@Test
	void aStaleTopUpPaidAtPaystackIsCreditedWithoutAWebhook() throws Exception {
		String intent = pendingTopUp();
		makeOld(intent, "15 minutes");
		paystackSays(intent, "success", "\"2026-11-18T10:15:30.000Z\"");

		assertThat(job.run().ran()).isTrue();

		assertThat(statusOf(intent)).isEqualTo("PAID");
		assertThat(walletBalance()).isEqualTo(AMOUNT);
		// Running again changes nothing: the intent is settled and no longer looked at.
		job.run();
		assertThat(walletBalance()).isEqualTo(AMOUNT);
		PAYSTACK.verify(1, getRequestedFor(urlPathEqualTo("/transaction/verify/" + intent)));
	}

	@Test
	void aTopUpLeftUnpaidForAnHourExpires() throws Exception {
		String intent = pendingTopUp();
		makeOld(intent, "61 minutes");
		paystackSays(intent, "abandoned", "null");

		job.run();

		assertThat(statusOf(intent)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("SELECT failure_reason FROM payment_intents WHERE id = ?::uuid", String.class,
				intent))
			.isEqualTo("Expired unpaid after 60 minutes");
	}

	@Test
	void anUnpaidTopUpYoungerThanAnHourIsKept() throws Exception {
		String intent = pendingTopUp();
		makeOld(intent, "20 minutes");
		paystackSays(intent, "abandoned", "null");

		job.run();

		assertThat(statusOf(intent)).isEqualTo("PAYMENT_PENDING");
	}

	@Test
	void aRecentTopUpIsLeftToItsWebhook() throws Exception {
		String intent = pendingTopUp();

		job.run();

		PAYSTACK.verify(0, getRequestedFor(urlPathEqualTo("/transaction/verify/" + intent)));
		assertThat(statusOf(intent)).isEqualTo("PAYMENT_PENDING");
	}

	@Test
	void aTopUpStuckBeforeItsPaymentPageFailsWhenPaystackDoesNotKnowIt() throws Exception {
		PAYSTACK.stubFor(post("/transaction/initialize").willReturn(aResponse().withStatus(503)));
		mvc.perform(topUp()).andExpect(status().isBadGateway());
		String intent = jdbc.queryForObject(
				"SELECT id::text FROM payment_intents WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1",
				String.class, tenantId());
		assertThat(statusOf(intent)).isEqualTo("INITIATED");
		makeOld(intent, "15 minutes");
		PAYSTACK.stubFor(get("/transaction/verify/" + intent).willReturn(aResponse().withStatus(400)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"status\": false, \"message\": \"Transaction reference not found\"}")));

		job.run();

		assertThat(statusOf(intent)).isEqualTo("FAILED");
	}

	@Test
	void onlyOneInstanceRunsAtATime() throws Exception {
		String intent = pendingTopUp();
		makeOld(intent, "15 minutes");
		paystackSays(intent, "success", "\"2026-11-18T10:15:30.000Z\"");

		// Another instance holds the lock on its own connection.
		try (Connection other = dataSource.getConnection()) {
			advisory(other, "SELECT pg_advisory_lock(?)");
			try {
				assertThat(job.run().ran()).isFalse();
				assertThat(statusOf(intent)).isEqualTo("PAYMENT_PENDING");
			}
			finally {
				advisory(other, "SELECT pg_advisory_unlock(?)");
			}
		}

		assertThat(job.run().ran()).isTrue();
		assertThat(statusOf(intent)).isEqualTo("PAID");
	}

	private static void advisory(Connection connection, String sql) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, 0x4D50_5245_434F_4E43L);
			statement.executeQuery().close();
		}
	}

	private void makeOld(String intent, String age) {
		// Ages the row instead of moving the clock: the job compares created_at with the real clock.
		jdbc.update("UPDATE payment_intents SET created_at = now() - ?::interval WHERE id = ?::uuid", age, intent);
	}

	private void paystackSays(String intent, String status, String paidAt) {
		PAYSTACK.stubFor(get("/transaction/verify/" + intent).willReturn(okJson("""
				{"status": true, "data": {"status": "%s", "reference": "%s", "amount": %d, "currency": "NGN",
				"paid_at": %s, "gateway_response": "Test"}}
				""".formatted(status, intent, AMOUNT, paidAt))));
	}

	private String pendingTopUp() throws Exception {
		return JsonPath.read(mvc.perform(topUp())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.intentId");
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder topUp() {
		return MockMvcRequestBuilders.post("/api/v1/wallet/top-ups")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant)
			.header("Idempotency-Key", "key-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amountKobo\": " + AMOUNT + "}");
	}

	private String statusOf(String intent) {
		return jdbc.queryForObject("SELECT status FROM payment_intents WHERE id = ?::uuid", String.class, intent);
	}

	private long walletBalance() throws Exception {
		Number balance = JsonPath.read(mvc.perform(MockMvcRequestBuilders.get("/api/v1/wallet")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant)).andReturn().getResponse().getContentAsString(),
				"$.balanceKobo");
		return balance.longValue();
	}

	private UUID tenantId() throws Exception {
		return UUID.fromString(JsonPath.read(mvc.perform(MockMvcRequestBuilders.get("/api/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant)).andReturn().getResponse().getContentAsString(),
				"$.id"));
	}

	private String tenantToken() throws Exception {
		String landlord = token("landlord-" + UUID.randomUUID() + "@example.com", null);
		String building = JsonPath.read(mvc.perform(MockMvcRequestBuilders.post("/api/v1/buildings")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + landlord)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "Sunrise Court", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
					""")).andReturn().getResponse().getContentAsString(), "$.id");
		String unit = JsonPath.read(mvc.perform(MockMvcRequestBuilders.post("/api/v1/buildings/" + building + "/units")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + landlord)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"label\": \"Flat 1\"}")).andReturn().getResponse().getContentAsString(), "$.id");
		String code = JsonPath.read(mvc.perform(MockMvcRequestBuilders.post("/api/v1/units/" + unit + "/invites")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + landlord)).andReturn().getResponse().getContentAsString(),
				"$.code");
		return token("tenant-" + UUID.randomUUID() + "@example.com", code);
	}

	private String token(String email, String inviteCode) throws Exception {
		String code = inviteCode == null ? "" : ", \"inviteCode\": \"" + inviteCode + "\"";
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/register")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Tolu Ade"%s}
					""".formatted(email, "+234806" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000), PASSWORD,
					code)))
			.andExpect(status().isCreated());
		return JsonPath.read(mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, PASSWORD)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.accessToken");
	}

}
