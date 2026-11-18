package com.theodomart.energy.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The reconciliation job's timings (DESIGN 8.1). The interval itself is read by @Scheduled from the same prefix.
 *
 * @param staleAfter how old an unsettled intent must be before the job asks about it; younger ones are still in the
 * tenant's hands, and the webhook usually arrives first
 * @param expireAfter how old a pending intent may get before a final verify that is still not paid fails it
 * @param batchSize the most intents one run looks at, so a backlog is worked off over several runs
 */
@ConfigurationProperties("meterpay.payment.reconciliation")
public record ReconciliationProperties(Duration interval, Duration staleAfter, Duration expireAfter, int batchSize) {

	public ReconciliationProperties {
		requirePositive(interval, "interval");
		requirePositive(staleAfter, "stale-after");
		requirePositive(expireAfter, "expire-after");
		if (expireAfter.compareTo(staleAfter) < 0) {
			throw new IllegalStateException("meterpay.payment.reconciliation.expire-after must not be shorter than "
					+ "stale-after");
		}
		if (batchSize < 1) {
			throw new IllegalStateException("meterpay.payment.reconciliation.batch-size must be at least 1");
		}
	}

	private static void requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException("meterpay.payment.reconciliation." + name + " must be positive");
		}
	}

}
