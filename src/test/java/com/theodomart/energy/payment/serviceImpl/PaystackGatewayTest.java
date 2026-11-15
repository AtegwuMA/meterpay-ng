package com.theodomart.energy.payment.serviceImpl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.payment.config.PaystackProperties;
import com.theodomart.energy.payment.service.PaymentGateway.Initialization;
import com.theodomart.energy.payment.service.PaymentGateway.Outcome;
import com.theodomart.energy.payment.service.PaymentGateway.Verification;
import com.theodomart.energy.payment.service.PaymentGatewayException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Paystack over real HTTP, stubbed by WireMock, so timeouts and broken answers behave as they would for real. */
@ExtendWith(OutputCaptureExtension.class)
class PaystackGatewayTest {

	private static final String SECRET = "sk_test_secret_that_must_never_leak";

	private static final Duration TIMEOUT = Duration.ofMillis(500);

	@RegisterExtension
	static WireMockExtension paystack = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	@Test
	void initializeSendsTheAmountInKoboWithTheSecretKeyAndReturnsThePaymentPage() {
		paystack.stubFor(post("/transaction/initialize").withHeader("Authorization", equalTo("Bearer " + SECRET))
			.withRequestBody(equalToJson("""
					{"email": "tolu@example.com", "amount": 500000, "currency": "NGN", "reference": "intent-1"}
					"""))
			.willReturn(okJson("""
					{"status": true, "message": "Authorization URL created",
					"data": {"authorization_url": "https://checkout.paystack.com/abc", "access_code": "abc",
							"reference": "intent-1"}}
					""")));

		Initialization result = gateway(null).initialize("intent-1", Money.ofKobo(500_000), "tolu@example.com");

		assertThat(result.authorizationUrl()).isEqualTo("https://checkout.paystack.com/abc");
		assertThat(result.reference()).isEqualTo("intent-1");
	}

	@Test
	void theCallbackUrlIsSentWhenOneIsConfigured() {
		paystack.stubFor(post("/transaction/initialize").willReturn(okJson(initialized())));

		gateway("https://app.example.com/top-ups/done").initialize("intent-2", Money.ofKobo(100), "a@example.com");

		paystack.verify(postRequestedFor(urlPathEqualTo("/transaction/initialize"))
			.withRequestBody(equalToJson("{\"callback_url\": \"https://app.example.com/top-ups/done\"}", true, true)));
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 401, 403, 404, 422 })
	void aRefusalWeCausedIsNotWorthRetrying(int status) {
		paystack.stubFor(post("/transaction/initialize").willReturn(aResponse().withStatus(status)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"status\": false, \"message\": \"Invalid key: " + SECRET + "\"}")));

		assertThatThrownBy(() -> initialize()).isInstanceOfSatisfying(PaymentGatewayException.class, ex -> {
			assertThat(ex.isRetryable()).isFalse();
			assertThat(ex.providerStatus()).isEqualTo(status);
			// Neither Paystack's words nor our secret reach the client.
			assertThat(ex.getMessage()).doesNotContain("Invalid key").doesNotContain(SECRET);
		});
	}

	@ParameterizedTest
	@ValueSource(ints = { 500, 502, 503, 429 })
	void aFailureOnThePaystackSideIsWorthRetrying(int status) {
		paystack.stubFor(post("/transaction/initialize").willReturn(aResponse().withStatus(status)));

		assertThatThrownBy(() -> initialize()).isInstanceOfSatisfying(PaymentGatewayException.class,
				ex -> assertThat(ex.isRetryable()).isTrue());
	}

	@Test
	void aSlowAnswerTimesOutInsteadOfHoldingTheRequest() {
		paystack.stubFor(post("/transaction/initialize").willReturn(okJson(initialized()).withFixedDelay(3_000)));
		long started = System.nanoTime();

		assertThatThrownBy(() -> initialize()).isInstanceOfSatisfying(PaymentGatewayException.class, ex -> {
			assertThat(ex.isRetryable()).isTrue();
			assertThat(ex.providerStatus()).isZero();
		});
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2_500));
	}

	@Test
	void aRefusedConnectionIsWorthRetrying() {
		PaystackGateway nowhere = new PaystackGateway(
				new PaystackProperties("http://localhost:1", SECRET, null, TIMEOUT, TIMEOUT));

		assertThatThrownBy(() -> nowhere.initialize("intent-3", Money.ofKobo(100), "a@example.com"))
			.isInstanceOfSatisfying(PaymentGatewayException.class, ex -> assertThat(ex.isRetryable()).isTrue());
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"status\": false, \"message\": \"Duplicate Transaction Reference\"}",
			"{\"status\": true, \"data\": {\"reference\": \"intent-1\"}}", "{\"status\": true}", "not json at all",
			"[]" })
	void anAnswerWeCannotUseIsAnErrorNotAGuess(String body) {
		paystack.stubFor(post("/transaction/initialize")
			.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));

		assertThatThrownBy(() -> initialize()).isInstanceOfSatisfying(PaymentGatewayException.class,
				ex -> assertThat(ex.isRetryable()).isFalse());
	}

	@Test
	void verifyReportsASuccessfulPaymentWithWhatWasCharged() {
		paystack.stubFor(get("/transaction/verify/intent-1").willReturn(okJson("""
				{"status": true, "message": "Verification successful",
				"data": {"status": "success", "reference": "intent-1", "amount": 500000, "currency": "NGN",
						"paid_at": "2026-11-15T10:15:30.000Z", "gateway_response": "Approved"}}
				""")));

		Verification result = gateway(null).verify("intent-1");

		assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
		assertThat(result.reference()).isEqualTo("intent-1");
		assertThat(result.amountKobo()).isEqualTo(500_000);
		assertThat(result.currency()).isEqualTo("NGN");
		assertThat(result.paidAt()).isEqualTo(Instant.parse("2026-11-15T10:15:30Z"));
		assertThat(result.providerMessage()).isEqualTo("Approved");
	}

	@Test
	void anUnfinishedPaymentHasNoPaidTime() {
		paystack.stubFor(get("/transaction/verify/intent-4").willReturn(okJson("""
				{"status": true, "data": {"status": "abandoned", "reference": "intent-4", "amount": 500000,
				"currency": "NGN", "paid_at": null, "gateway_response": "The transaction was not completed"}}
				""")));

		Verification result = gateway(null).verify("intent-4");

		assertThat(result.outcome()).isEqualTo(Outcome.PENDING);
		assertThat(result.paidAt()).isNull();
	}

	@Test
	void aSuccessWithoutAPaidTimeIsNotBelieved() {
		paystack.stubFor(get("/transaction/verify/intent-5").willReturn(okJson("""
				{"status": true, "data": {"status": "success", "reference": "intent-5", "amount": 500000,
				"currency": "NGN"}}
				""")));

		assertThatThrownBy(() -> gateway(null).verify("intent-5")).isInstanceOf(PaymentGatewayException.class);
	}

	@Test
	void verifyWithoutAnAmountIsNotBelieved() {
		paystack.stubFor(get("/transaction/verify/intent-6")
			.willReturn(okJson("{\"status\": true, \"data\": {\"status\": \"success\", \"amount\": \"lots\"}}")));

		assertThatThrownBy(() -> gateway(null).verify("intent-6")).isInstanceOf(PaymentGatewayException.class);
	}

	@Test
	void theReferenceIsEncodedIntoThePath() {
		paystack.stubFor(get(urlPathEqualTo("/transaction/verify/odd%20ref%2F1")).willReturn(okJson("""
				{"status": true, "data": {"status": "failed", "reference": "odd ref/1", "amount": 100,
				"currency": "NGN"}}
				""")));

		assertThat(gateway(null).verify("odd ref/1").outcome()).isEqualTo(Outcome.FAILED);
	}

	@ParameterizedTest
	@CsvSource({ "success, SUCCESS", "SUCCESS, SUCCESS", "failed, FAILED", "reversed, FAILED", "abandoned, PENDING",
			"ongoing, PENDING", "pending, PENDING", "processing, PENDING", "queued, PENDING", "something_new, PENDING" })
	void paystackStatusesMapToThreeOutcomes(String status, Outcome outcome) {
		assertThat(PaystackGateway.outcomeOf(status)).isEqualTo(outcome);
	}

	@Test
	void theSecretKeyNeverReachesTheLogs(CapturedOutput output) {
		paystack.stubFor(post("/transaction/initialize").willReturn(aResponse().withStatus(401)));
		paystack.stubFor(get("/transaction/verify/intent-7").willReturn(aResponse().withStatus(500)));

		assertThatThrownBy(() -> initialize()).isInstanceOf(PaymentGatewayException.class);
		assertThatThrownBy(() -> gateway(null).verify("intent-7")).isInstanceOf(PaymentGatewayException.class);

		assertThat(output).contains("Paystack initialize refused").doesNotContain(SECRET);
	}

	private Initialization initialize() {
		return gateway(null).initialize("intent-1", Money.ofKobo(500_000), "tolu@example.com");
	}

	private static PaystackGateway gateway(String callbackUrl) {
		return new PaystackGateway(new PaystackProperties(paystack.baseUrl(), SECRET, callbackUrl, TIMEOUT, TIMEOUT));
	}

	private static String initialized() {
		return """
				{"status": true, "data": {"authorization_url": "https://checkout.paystack.com/x", "reference": "intent-1"}}
				""";
	}

}
