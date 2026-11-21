package com.theodomart.energy.payment.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Paystack's webhook from HTTP to the ledger, with Paystack's verify answered by a stand-in on a real port. */
@AutoConfigureMockMvc
class PaystackWebhookIT extends IntegrationTest {

	/** The test profile's key (application-test.yml). Paystack signs webhooks with the secret key. */
	private static final String SECRET = "sk_test_not_a_real_key";

	private static final String WEBHOOK = "/api/v1/webhooks/paystack";

	private static final String PASSWORD = "correct-horse-battery";

	private static final long AMOUNT = 500_000;

	private static final WireMockServer PAYSTACK = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		PAYSTACK.start();
	}

	@DynamicPropertySource
	static void pointPaystackAtTheStub(DynamicPropertyRegistry registry) {
		// The default read timeout on purpose: nothing here is about timing out, and a cold stub can be slow.
		registry.add("meterpay.payment.paystack.base-url", PAYSTACK::baseUrl);
	}

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private String tenant;

	private String intentId;

	@Autowired
	PaystackWebhookIT(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void aTenantWaitingOnAPayment() throws Exception {
		PAYSTACK.resetAll();
		PAYSTACK.stubFor(post("/transaction/initialize").willReturn(okJson("""
				{"status": true, "data": {"authorization_url": "https://checkout.paystack.com/ok",
				"reference": "{{jsonPath request.body '$.reference'}}"}}
				""").withTransformers("response-template")));
		tenant = tenantToken();
		intentId = JsonPath.read(mvc.perform(MockMvcRequestBuilders.post("/api/v1/wallet/top-ups")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant)
			.header("Idempotency-Key", "key-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amountKobo\": " + AMOUNT + "}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.intentId");
	}

	@Test
	void duplicateWebhookCreditsWalletOnce() throws Exception {
		paystackConfirms(AMOUNT, "NGN");
		byte[] body = chargeSuccess(intentId, AMOUNT);
		int deliveries = 6;
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<Integer>> attempts = new ArrayList<>();
		for (int i = 0; i < deliveries; i++) {
			attempts.add(() -> {
				start.await();
				return deliver(body, sign(body)).andReturn().getResponse().getStatus();
			});
		}
		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(deliveries)) {
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> attempt : attempts) {
				futures.add(pool.submit(attempt));
			}
			start.countDown();
			for (Future<Integer> future : futures) {
				statuses.add(future.get());
			}
		}
		// And once more after the dust settles, as Paystack's own retry would.
		deliver(body, sign(body)).andExpect(status().isOk());

		assertThat(statuses).containsOnly(200);
		assertThat(walletBalance()).isEqualTo(AMOUNT);
		assertThat(intentStatus()).isEqualTo("PAID");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class,
				"topup:" + intentId))
			.isOne();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM webhook_receipts
				WHERE payload -> 'data' ->> 'reference' = ? AND signature_valid AND processed_at IS NOT NULL
				""", Integer.class, intentId)).isOne();
		// One event for other modules, however many deliveries.
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND event_type = 'WalletToppedUp'",
				Integer.class, intentId))
			.isOne();
		// The log has every delivery, repeats included, and the verify calls the deliveries made.
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM payment_events WHERE intent_id = ?::uuid AND event_type = 'WEBHOOK_RECEIVED'",
				Integer.class, intentId))
			.isEqualTo(deliveries + 1);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM payment_events WHERE intent_id = ?::uuid AND event_type = 'VERIFY_SUCCEEDED'",
				Integer.class, intentId))
			.isPositive();
	}

	@Test
	void invalidSignatureIsRejected() throws Exception {
		paystackConfirms(AMOUNT, "NGN");
		byte[] body = chargeSuccess(intentId, AMOUNT);
		byte[] tampered = chargeSuccess(intentId, AMOUNT * 100);

		deliver(body, sign(body).replace('a', 'b').replace('1', '2')).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		deliver(tampered, sign(body)).andExpect(status().isUnauthorized());
		deliver(body, null).andExpect(status().isUnauthorized());
		deliver("not json".getBytes(StandardCharsets.UTF_8), "00").andExpect(status().isUnauthorized());

		assertThat(walletBalance()).isZero();
		assertThat(intentStatus()).isEqualTo("PAYMENT_PENDING");
		// Nothing a forger sends makes us call Paystack, and every attempt is kept, unprocessed.
		PAYSTACK.verify(0, getRequestedFor(urlPathMatching("/transaction/verify/.*")));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM webhook_receipts WHERE NOT signature_valid AND processed_at IS NULL "
						+ "AND payload -> 'data' ->> 'reference' = ?",
				Integer.class, intentId))
			.isEqualTo(3);
	}

	@Test
	void aForgedCopyArrivingFirstDoesNotBlockTheGenuineWebhook() throws Exception {
		paystackConfirms(AMOUNT, "NGN");
		byte[] body = chargeSuccess(intentId, AMOUNT);

		deliver(body, "f".repeat(128)).andExpect(status().isUnauthorized());
		deliver(body, sign(body)).andExpect(status().isOk());

		assertThat(walletBalance()).isEqualTo(AMOUNT);
	}

	@Test
	void aConfirmationForAnotherAmountFailsTheTopUpInsteadOfCreditingIt() throws Exception {
		paystackConfirms(10_000, "NGN");
		byte[] body = chargeSuccess(intentId, AMOUNT);

		deliver(body, sign(body)).andExpect(status().isOk());

		assertThat(walletBalance()).isZero();
		assertThat(intentStatus()).isEqualTo("FAILED");
	}

	@Test
	void aConfirmationInAnotherCurrencyIsNotBelievedEither() throws Exception {
		paystackConfirms(AMOUNT, "USD");
		byte[] body = chargeSuccess(intentId, AMOUNT);

		deliver(body, sign(body)).andExpect(status().isOk());

		assertThat(walletBalance()).isZero();
		assertThat(intentStatus()).isEqualTo("FAILED");
	}

	@Test
	void whenPaystackCannotConfirmTheWebhookFailsSoPaystackSendsItAgain() throws Exception {
		PAYSTACK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/transaction/verify/" + intentId)
			.willReturn(aResponse().withStatus(503)));
		byte[] body = chargeSuccess(intentId, AMOUNT);

		deliver(body, sign(body)).andExpect(status().isBadGateway());
		assertThat(intentStatus()).isEqualTo("PAYMENT_PENDING");

		paystackConfirms(AMOUNT, "NGN");
		deliver(body, sign(body)).andExpect(status().isOk());

		assertThat(walletBalance()).isEqualTo(AMOUNT);
	}

	@Test
	void aWebhookForAPaymentThatIsNotOursIsAcknowledgedAndIgnored() throws Exception {
		byte[] stranger = chargeSuccess("someone-elses-reference", AMOUNT);
		byte[] refund = """
				{"event": "refund.processed", "data": {"id": 99, "reference": "%s"}}
				""".formatted(intentId).getBytes(StandardCharsets.UTF_8);

		deliver(stranger, sign(stranger)).andExpect(status().isOk());
		deliver(refund, sign(refund)).andExpect(status().isOk());

		PAYSTACK.verify(0, getRequestedFor(urlPathMatching("/transaction/verify/.*")));
		assertThat(intentStatus()).isEqualTo("PAYMENT_PENDING");
	}

	@Test
	void aSignedBodyThatIsNotAnEventIsABadRequest() throws Exception {
		byte[] body = "{\"hello\": \"world\"}".getBytes(StandardCharsets.UTF_8);

		deliver(body, sign(body)).andExpect(status().isBadRequest());
	}

	private void paystackConfirms(long amountKobo, String currency) {
		PAYSTACK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/transaction/verify/" + intentId)
			.willReturn(okJson("""
					{"status": true, "data": {"status": "success", "reference": "%s", "amount": %d,
					"currency": "%s", "paid_at": "2026-11-18T10:15:30.000Z", "gateway_response": "Approved"}}
					""".formatted(intentId, amountKobo, currency))));
	}

	private static byte[] chargeSuccess(String reference, long amountKobo) {
		// Paystack's transaction id, which with the event type names the event for deduplication.
		long transactionId = Math.abs(reference.hashCode());
		return """
				{"event": "charge.success", "data": {"id": %d, "status": "success", "reference": "%s",
				"amount": %d, "currency": "NGN", "paid_at": "2026-11-18T10:15:30.000Z"}}
				""".formatted(transactionId, reference, amountKobo).getBytes(StandardCharsets.UTF_8);
	}

	private static String sign(byte[] body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA512");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
		return HexFormat.of().formatHex(mac.doFinal(body));
	}

	private ResultActions deliver(byte[] body, String signature) throws Exception {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(WEBHOOK)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		if (signature != null) {
			request.header("x-paystack-signature", signature);
		}
		return mvc.perform(request);
	}

	private long walletBalance() throws Exception {
		Number balance = JsonPath.read(mvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + tenant)).andReturn().getResponse().getContentAsString(), "$.balanceKobo");
		return balance.longValue();
	}

	private String intentStatus() {
		return jdbc.queryForObject("SELECT status FROM payment_intents WHERE id = ?::uuid", String.class, intentId);
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
