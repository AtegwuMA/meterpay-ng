package com.theodomart.energy.property.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/** Invite codes are read out or typed on a phone, so they are short and avoid look-alike characters. */
public final class InviteCode {

	// No 0/O, 1/I/L or U. 30 symbols over 10 positions is about 49 bits: too many to guess online once the
	// register endpoint is rate limited (M8-01), for a code that lives 7 days and works once.
	private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

	private static final int LENGTH = 10;

	private static final SecureRandom RANDOM = new SecureRandom();

	private InviteCode() {
	}

	/** For example {@code K7MPQ-2XR9T}. */
	public static String generate() {
		StringBuilder code = new StringBuilder(LENGTH + 1);
		for (int i = 0; i < LENGTH; i++) {
			if (i == LENGTH / 2) {
				code.append('-');
			}
			code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
		}
		return code.toString();
	}

	/** Hash of the canonical form: however the tenant typed it (case, spaces, dashes), the same code matches. */
	public static String hash(String code) {
		String canonical = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required on every Java platform", ex);
		}
	}

}
