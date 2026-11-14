package com.theodomart.energy.ledger.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.TrialBalanceView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LedgerImbalanceMonitorTest {

	private final LedgerService ledger = mock();

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final LedgerImbalanceMonitor monitor = new LedgerImbalanceMonitor(ledger, registry);

	@Test
	void aBalancedLedgerSetsBothGaugesToZero(CapturedOutput output) {
		when(ledger.trialBalance()).thenReturn(result(0, 0));

		monitor.check();

		assertThat(gauge(LedgerImbalanceMonitor.IMBALANCE_GAUGE)).isZero();
		assertThat(gauge(LedgerImbalanceMonitor.MISMATCH_GAUGE)).isZero();
		assertThat(output).doesNotContain("does not balance");
	}

	@Test
	void anImbalanceIsReportedInTheGaugeAndAtError(CapturedOutput output) {
		when(ledger.trialBalance()).thenReturn(result(-150, 0));

		monitor.check();

		assertThat(gauge(LedgerImbalanceMonitor.IMBALANCE_GAUGE)).isEqualTo(-150);
		assertThat(output).contains("ERROR").contains("imbalanceKobo=-150");
	}

	@Test
	void driftIsReportedEvenWhenTheTotalsHappenToCancelOut(CapturedOutput output) {
		when(ledger.trialBalance()).thenReturn(result(0, 2));

		monitor.check();

		assertThat(gauge(LedgerImbalanceMonitor.MISMATCH_GAUGE)).isEqualTo(2);
		assertThat(output).contains("accountsNotMatchingPostings=2");
	}

	@Test
	void theGaugesFollowTheLatestCheck() {
		when(ledger.trialBalance()).thenReturn(result(10, 1), result(0, 0));

		monitor.check();
		monitor.check();

		assertThat(gauge(LedgerImbalanceMonitor.IMBALANCE_GAUGE)).isZero();
		assertThat(gauge(LedgerImbalanceMonitor.MISMATCH_GAUGE)).isZero();
	}

	private double gauge(String name) {
		return registry.get(name).gauge().value();
	}

	private static TrialBalanceView result(long imbalance, long mismatched) {
		return new TrialBalanceView(List.of(), 0, 0, imbalance, mismatched, imbalance == 0 && mismatched == 0,
				Instant.parse("2026-11-14T09:00:00Z"));
	}

}
