package com.theodomart.energy.payment.serviceImpl;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.payment.config.PaystackProperties;
import com.theodomart.energy.payment.service.PaymentGateway;
import com.theodomart.energy.payment.service.PaymentGatewayException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Paystack's transaction API: initialize and verify. Every way a call can go wrong ends as a PaymentGatewayException,
 * so a caller has one thing to handle and never sees a raw HTTP or JSON error.
 */
@Component
public class PaystackGateway implements PaymentGateway {

	private static final Logger log = LoggerFactory.getLogger(PaystackGateway.class);

	private static final String CURRENCY = "NGN";

	private final RestClient client;

	private final String callbackUrl;

	public PaystackGateway(PaystackProperties properties) {
		// Both timeouts are explicit. The JDK's defaults wait for ever, and a hung provider would hold a request
		// thread, and with it a database connection, for as long as it liked.
		// HTTP/1.1 on purpose: the JDK client otherwise tries an HTTP/2 upgrade on plain http, which some servers
		// cancel mid-request when there is a body. A few small JSON calls gain nothing from HTTP/2.
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(properties.connectTimeout())
			.build());
		requestFactory.setReadTimeout(properties.readTimeout());
		this.client = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory(requestFactory)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.secretKey())
			.build();
		this.callbackUrl = properties.callbackUrl();
	}

	@Override
	public Initialization initialize(String reference, Money amount, String email) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("email", email);
		// Paystack takes amounts in the currency's minor unit, which for naira is kobo: no conversion, no rounding.
		request.put("amount", amount.kobo());
		request.put("currency", CURRENCY);
		request.put("reference", reference);
		if (callbackUrl != null) {
			request.put("callback_url", callbackUrl);
		}
		JsonNode data = call("initialize", reference,
				() -> client.post().uri("/transaction/initialize").contentType(MediaType.APPLICATION_JSON).body(request));
		String authorizationUrl = text(data, "authorization_url");
		String returnedReference = text(data, "reference");
		if (authorizationUrl == null || returnedReference == null) {
			throw unreadable("initialize", reference, "no authorization_url or reference");
		}
		return new Initialization(authorizationUrl, returnedReference);
	}

	@Override
	public Verification verify(String reference) {
		JsonNode data = call("verify", reference,
				() -> client.get().uri("/transaction/verify/{reference}", reference));
		String status = text(data, "status");
		if (status == null || !data.path("amount").isIntegralNumber()) {
			throw unreadable("verify", reference, "no status or amount");
		}
		Outcome outcome = outcomeOf(status);
		Instant paidAt = outcome == Outcome.SUCCESS ? instant(data, "paid_at", reference) : null;
		return new Verification(outcome, text(data, "reference"), data.path("amount").asLong(), text(data, "currency"),
				paidAt, text(data, "gateway_response"));
	}

	/**
	 * Paystack's transaction statuses. Anything it might be still working on, and anything new it adds later, counts as
	 * PENDING: asking again is harmless, but calling an unfinished payment FAILED could refuse a tenant money they paid.
	 */
	static Outcome outcomeOf(String status) {
		return switch (status.toLowerCase(Locale.ROOT)) {
			case "success" -> Outcome.SUCCESS;
			case "failed", "reversed" -> Outcome.FAILED;
			default -> Outcome.PENDING;
		};
	}

	private JsonNode call(String operation, String reference, java.util.function.Supplier<RestClient.RequestHeadersSpec<?>> request) {
		long started = System.nanoTime();
		JsonNode body;
		try {
			body = request.get()
				.retrieve()
				.onStatus(HttpStatusCode::isError, (req, response) -> {
					throw PaymentGatewayException.rejected(response.getStatusCode().value());
				})
				.body(JsonNode.class);
		}
		catch (PaymentGatewayException ex) {
			log.warn("Paystack {} refused: reference={} status={} retryable={}", operation, reference,
					ex.providerStatus(), ex.isRetryable());
			throw ex;
		}
		catch (ResourceAccessException ex) {
			// Timeouts and refused connections. The request may have reached Paystack, so the caller must not assume
			// it did not: verify, do not initialize again.
			log.warn("Paystack {} unreachable: reference={} after={}ms", operation, reference, elapsedMillis(started));
			throw PaymentGatewayException.unreachable(ex);
		}
		catch (RestClientException ex) {
			throw unreadable(operation, reference, ex.getClass().getSimpleName());
		}
		if (body == null || !body.path("status").asBoolean(false) || !body.path("data").isObject()) {
			throw unreadable(operation, reference, "status was not true");
		}
		log.info("Paystack {} answered: reference={} in={}ms", operation, reference, elapsedMillis(started));
		return body.path("data");
	}

	private static PaymentGatewayException unreadable(String operation, String reference, String why) {
		log.warn("Paystack {} answer could not be used: reference={} reason={}", operation, reference, why);
		return PaymentGatewayException.unreadable();
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isString() && !value.asString().isBlank() ? value.asString() : null;
	}

	private static Instant instant(JsonNode node, String field, String reference) {
		String value = text(node, field);
		if (value == null) {
			throw unreadable("verify", reference, "a successful payment has no " + field);
		}
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException ex) {
			throw unreadable("verify", reference, field + " is not a timestamp");
		}
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}

}
