package com.theodomart.energy.common.domain;

/**
 * An amount of electrical energy in whole watt-hours; see DESIGN §6.1.
 *
 * <p>Signed, unlike {@link Money}: meter credit entries carry signed deltas, and a meter's credit
 * can overshoot below zero between the last reading and the cutoff (DESIGN §6.2, §7.2).
 */
public record Energy(long wh) implements Comparable<Energy> {

	public static final Energy ZERO = new Energy(0);

	public static Energy ofWh(long wh) {
		return new Energy(wh);
	}

	/** @throws ArithmeticException if the sum overflows a long */
	public Energy add(Energy other) {
		return new Energy(Math.addExact(wh, other.wh));
	}

	/** @throws ArithmeticException if the difference overflows a long */
	public Energy subtract(Energy other) {
		return new Energy(Math.subtractExact(wh, other.wh));
	}

	public boolean isPositive() {
		return wh > 0;
	}

	public boolean isZeroOrNegative() {
		return wh <= 0;
	}

	@Override
	public int compareTo(Energy other) {
		return Long.compare(wh, other.wh);
	}

}
