package com.theodomart.energy.common.domain;

import java.util.Locale;

/**
 * An amount of Nigerian naira held as whole kobo (1 naira = 100 kobo). Integer minor units keep
 * every calculation exact; see DESIGN §6.1.
 *
 * <p>Never negative: postings are always positive and a wallet can never go below zero (DESIGN
 * §6.3). Direction lives on the posting, not in the sign of the amount.
 */
public record Money(long kobo) implements Comparable<Money> {

	public static final Money ZERO = new Money(0);

	private static final long KOBO_PER_NAIRA = 100;

	public Money {
		if (kobo < 0) {
			throw new IllegalArgumentException("Money cannot be negative: " + kobo + " kobo");
		}
	}

	public static Money ofKobo(long kobo) {
		return new Money(kobo);
	}

	public static Money ofNaira(long naira) {
		return new Money(Math.multiplyExact(naira, KOBO_PER_NAIRA));
	}

	/** @throws ArithmeticException if the sum overflows a long */
	public Money add(Money other) {
		return new Money(Math.addExact(kobo, other.kobo));
	}

	/**
	 * Callers that can legitimately be short (a wallet paying for a purchase) must check
	 * {@link #isLessThan} first and raise their own domain error.
	 *
	 * @throws IllegalArgumentException if the result would be negative
	 */
	public Money subtract(Money other) {
		if (other.kobo > kobo) {
			throw new IllegalArgumentException(
					"Cannot subtract " + other.kobo + " kobo from " + kobo + " kobo: result would be negative");
		}
		return new Money(kobo - other.kobo);
	}

	public boolean isZero() {
		return kobo == 0;
	}

	public boolean isLessThan(Money other) {
		return kobo < other.kobo;
	}

	/** Formats for people, for example {@code ₦1,200.00}. Integer arithmetic only, no floating point. */
	public String format() {
		long naira = kobo / KOBO_PER_NAIRA;
		long remainder = kobo % KOBO_PER_NAIRA;
		return String.format(Locale.ENGLISH, "₦%,d.%02d", naira, remainder);
	}

	@Override
	public int compareTo(Money other) {
		return Long.compare(kobo, other.kobo);
	}

}
