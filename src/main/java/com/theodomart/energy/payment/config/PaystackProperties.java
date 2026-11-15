package com.theodomart.energy.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to reach Paystack. The secret key has no default, like JWT_SECRET: without one the app refuses to start rather
 * than failing on a tenant's first top-up.
 *
 * @param callbackUrl where Paystack sends the tenant's browser after paying; optional, Paystack then uses the
 * dashboard's setting
 */
@ConfigurationProperties("meterpay.payment.paystack")
public record PaystackProperties(String baseUrl, String secretKey, String callbackUrl, Duration connectTimeout,
		Duration readTimeout) {

	public PaystackProperties {
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new IllegalStateException("meterpay.payment.paystack.base-url must be set");
		}
		// The check is on the prefix only: pasting the public key (pk_...) by mistake is the likely error.
		if (secretKey == null || !secretKey.strip().startsWith("sk_")) {
			throw new IllegalStateException("PAYSTACK_SECRET_KEY must be set to a Paystack secret key (sk_...)");
		}
		secretKey = secretKey.strip();
		callbackUrl = callbackUrl == null || callbackUrl.isBlank() ? null : callbackUrl.strip();
		requirePositive(connectTimeout, "connect-timeout");
		requirePositive(readTimeout, "read-timeout");
	}

	/** Records print every field by default, which would put the secret key in any log that prints this. */
	@Override
	public String toString() {
		return "PaystackProperties[baseUrl=" + baseUrl + ", secretKey=[redacted], callbackUrl=" + callbackUrl
				+ ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
	}

	private static void requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException("meterpay.payment.paystack." + name + " must be positive");
		}
	}

}
