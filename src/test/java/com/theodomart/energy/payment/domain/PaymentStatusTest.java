package com.theodomart.energy.payment.domain;

import static com.theodomart.energy.payment.domain.PaymentStatus.FAILED;
import static com.theodomart.energy.payment.domain.PaymentStatus.INITIATED;
import static com.theodomart.energy.payment.domain.PaymentStatus.PAID;
import static com.theodomart.energy.payment.domain.PaymentStatus.PAYMENT_PENDING;
import static com.theodomart.energy.payment.domain.PaymentStatus.REFUNDED;
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

class PaymentStatusTest {

	// Written out from DESIGN 7.1, independently of the code, so a wrong edit to either one fails.
	private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
			INITIATED, EnumSet.of(PAYMENT_PENDING, FAILED),
			PAYMENT_PENDING, EnumSet.of(PAID, FAILED),
			PAID, EnumSet.of(REFUNDED),
			FAILED, EnumSet.noneOf(PaymentStatus.class),
			REFUNDED, EnumSet.noneOf(PaymentStatus.class));

	static List<Arguments> everyPair() {
		List<Arguments> pairs = new ArrayList<>();
		for (PaymentStatus from : PaymentStatus.values()) {
			for (PaymentStatus to : PaymentStatus.values()) {
				pairs.add(Arguments.of(from, to, ALLOWED.get(from).contains(to)));
			}
		}
		return pairs;
	}

	@ParameterizedTest(name = "{0} -> {1}: {2}")
	@MethodSource("everyPair")
	void everyMoveIsAllowedExactlyWhenTheDesignSaysSo(PaymentStatus from, PaymentStatus to, boolean allowed) {
		assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
	}

	@Test
	void theTableCoversEveryState() {
		assertThat(ALLOWED.keySet()).containsExactlyInAnyOrder(PaymentStatus.values());
	}

	@Test
	void noStateMovesToItself() {
		for (PaymentStatus status : PaymentStatus.values()) {
			assertThat(status.canTransitionTo(status)).as(status.name()).isFalse();
		}
	}

	@Test
	void onlyFailedAndRefundedAreFinal() {
		assertThat(EnumSet.allOf(PaymentStatus.class)).filteredOn(PaymentStatus::isFinal)
			.containsExactlyInAnyOrder(FAILED, REFUNDED);
	}

}
