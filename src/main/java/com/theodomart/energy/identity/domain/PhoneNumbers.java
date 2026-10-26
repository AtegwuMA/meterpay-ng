package com.theodomart.energy.identity.domain;

import java.util.regex.Pattern;

/**
 * Turns the ways Nigerians write a phone number into the one E.164 form the users table accepts, so
 * the UNIQUE constraint compares like with like.
 */
public final class PhoneNumbers {

	private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");

	private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");

	private static final Pattern NIGERIAN_LOCAL = Pattern.compile("0\\d{10}");

	private static final Pattern NIGERIAN_NO_PLUS = Pattern.compile("234\\d{10}");

	private PhoneNumbers() {
	}

	/**
	 * Accepts {@code +2348031234567}, {@code 2348031234567} and {@code 08031234567}, with or without
	 * spaces, dashes, dots or brackets. Anything else must already be E.164.
	 *
	 * @throws IllegalArgumentException if the number fits none of these forms
	 */
	public static String toE164(String raw) {
		if (raw == null) {
			throw new IllegalArgumentException("Phone number is required");
		}
		String digits = SEPARATORS.matcher(raw.strip()).replaceAll("");
		if (NIGERIAN_LOCAL.matcher(digits).matches()) {
			return "+234" + digits.substring(1);
		}
		if (NIGERIAN_NO_PLUS.matcher(digits).matches()) {
			return "+" + digits;
		}
		if (E164.matcher(digits).matches()) {
			return digits;
		}
		throw new IllegalArgumentException("Phone number must be a Nigerian number (08031234567) or in E.164 format");
	}

}
