package com.theodomart.energy.meter.domain;

import static com.theodomart.energy.meter.domain.MeterStatus.ACTIVE;
import static com.theodomart.energy.meter.domain.MeterStatus.CUTOFF;
import static com.theodomart.energy.meter.domain.MeterStatus.DECOMMISSIONED;
import static com.theodomart.energy.meter.domain.MeterStatus.LOW_CREDIT;
import static com.theodomart.energy.meter.domain.MeterStatus.PROVISIONED;
import static com.theodomart.energy.meter.domain.MeterStatus.SUSPENDED;
import static com.theodomart.energy.meter.domain.MeterStatus.TAMPER_SUSPECTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MeterStatusTest {

	// Written out from DESIGN 7.2, independently of the code, so a wrong edit to either one fails.
	private static final Map<MeterStatus, Set<MeterStatus>> ALLOWED = Map.of(
			PROVISIONED, EnumSet.of(ACTIVE, SUSPENDED, DECOMMISSIONED),
			ACTIVE, EnumSet.of(LOW_CREDIT, CUTOFF, TAMPER_SUSPECTED, SUSPENDED, DECOMMISSIONED),
			LOW_CREDIT, EnumSet.of(ACTIVE, CUTOFF, TAMPER_SUSPECTED, SUSPENDED, DECOMMISSIONED),
			CUTOFF, EnumSet.of(ACTIVE, TAMPER_SUSPECTED, SUSPENDED, DECOMMISSIONED),
			TAMPER_SUSPECTED, EnumSet.of(ACTIVE, LOW_CREDIT, CUTOFF, SUSPENDED, DECOMMISSIONED),
			SUSPENDED, EnumSet.of(PROVISIONED, ACTIVE, LOW_CREDIT, CUTOFF, TAMPER_SUSPECTED, DECOMMISSIONED),
			DECOMMISSIONED, EnumSet.noneOf(MeterStatus.class));

	static List<Arguments> everyPair() {
		List<Arguments> pairs = new ArrayList<>();
		for (MeterStatus from : MeterStatus.values()) {
			for (MeterStatus to : MeterStatus.values()) {
				pairs.add(Arguments.of(from, to, ALLOWED.get(from).contains(to)));
			}
		}
		return pairs;
	}

	@ParameterizedTest(name = "{0} -> {1}: {2}")
	@MethodSource("everyPair")
	void everyTransitionPairMatchesTheDesign(MeterStatus from, MeterStatus to, boolean allowed) {
		assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
	}

	@Test
	void theTableCoversEveryPair() {
		assertThat(ALLOWED).containsOnlyKeys(MeterStatus.values());
		assertThat(everyPair()).hasSize(MeterStatus.values().length * MeterStatus.values().length);
	}

	@Test
	void noStateCanMoveToItself() {
		for (MeterStatus status : MeterStatus.values()) {
			assertThat(status.canTransitionTo(status)).as(status.name()).isFalse();
		}
	}

	@Test
	void everyStateExceptTheTerminalOneCanBeDecommissioned() {
		for (MeterStatus status : EnumSet.complementOf(EnumSet.of(DECOMMISSIONED))) {
			assertThat(status.canTransitionTo(DECOMMISSIONED)).as(status.name()).isTrue();
		}
	}

	@Test
	void aPurchaseOnACutOffMeterGoesThroughActive() {
		assertThat(CUTOFF.canTransitionTo(ACTIVE)).isTrue();
		assertThat(CUTOFF.canTransitionTo(LOW_CREDIT)).isFalse();
	}

	@Test
	void anUnreadMeterCannotBeTamperedWith() {
		assertThat(PROVISIONED.canTransitionTo(TAMPER_SUSPECTED)).isFalse();
		assertThat(PROVISIONED.canTransitionTo(LOW_CREDIT)).isFalse();
		assertThat(PROVISIONED.canTransitionTo(CUTOFF)).isFalse();
	}

}
