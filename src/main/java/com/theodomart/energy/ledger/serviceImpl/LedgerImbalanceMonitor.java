package com.theodomart.energy.ledger.serviceImpl;

import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.TrialBalanceView;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks the whole ledger on a schedule (DESIGN 6.3) and reports the result as two gauges, so an alert can fire on
 * anything other than zero. Until the alerting module exists (M7), a failure is also logged at ERROR.
 */
@Component
public class LedgerImbalanceMonitor {

	static final String IMBALANCE_GAUGE = "meterpay.ledger.imbalance";

	static final String MISMATCH_GAUGE = "meterpay.ledger.accounts.mismatched";

	private static final Logger log = LoggerFactory.getLogger(LedgerImbalanceMonitor.class);

	private final LedgerService ledger;

	private final AtomicLong imbalance = new AtomicLong();

	private final AtomicLong mismatched = new AtomicLong();

	public LedgerImbalanceMonitor(LedgerService ledger, MeterRegistry registry) {
		this.ledger = ledger;
		Gauge.builder(IMBALANCE_GAUGE, imbalance, AtomicLong::get)
			.description("Debit-normal minus credit-normal balances, in kobo. Anything but 0 is a bug.")
			.baseUnit("kobo")
			.register(registry);
		Gauge.builder(MISMATCH_GAUGE, mismatched, AtomicLong::get)
			.description("Ledger accounts whose stored balance differs from the sum of their postings.")
			.register(registry);
	}

	@Scheduled(initialDelayString = "${meterpay.ledger.imbalance-check-interval}",
			fixedDelayString = "${meterpay.ledger.imbalance-check-interval}")
	public TrialBalanceView check() {
		TrialBalanceView result = ledger.trialBalance();
		imbalance.set(result.imbalanceKobo());
		mismatched.set(result.accountsNotMatchingPostings());
		if (result.balanced()) {
			log.debug("Ledger check passed");
		}
		else {
			log.error("Ledger does not balance: imbalanceKobo={} accountsNotMatchingPostings={}",
					result.imbalanceKobo(), result.accountsNotMatchingPostings());
		}
		return result;
	}

}
