package com.theodomart.energy.common.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing error codes (DESIGN §10). Clients branch on {@code code}, never on
 * {@code title} or {@code detail}, so names here must not change once released.
 */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
	ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT, "Illegal state transition"),
	CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification"),
	IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused"),
	INSUFFICIENT_WALLET_BALANCE(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient wallet balance"),
	METER_NOT_OPERATIONAL(HttpStatus.UNPROCESSABLE_CONTENT, "Meter not operational"),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
	PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "Payment gateway error"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

	private static final String TYPE_BASE = "https://meterpay.theodomart.com/problems/";

	private final HttpStatus status;

	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}

	/** For example {@code .../problems/insufficient-wallet-balance}. */
	public URI type() {
		return URI.create(TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
	}

	/**
	 * Code for errors raised by Spring MVC itself (unknown route, wrong method, bad JSON), which
	 * carry a status but no domain meaning.
	 */
	static ErrorCode forFrameworkStatus(int status) {
		return switch (status) {
			case 400 -> VALIDATION_FAILED;
			case 401 -> UNAUTHENTICATED;
			case 403 -> FORBIDDEN;
			case 404 -> NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 406 -> NOT_ACCEPTABLE;
			case 415 -> UNSUPPORTED_MEDIA_TYPE;
			default -> status >= 500 ? INTERNAL_ERROR : VALIDATION_FAILED;
		};
	}

}
