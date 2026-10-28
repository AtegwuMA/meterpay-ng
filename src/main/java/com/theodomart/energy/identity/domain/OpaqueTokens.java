package com.theodomart.energy.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random opaque tokens and the hash under which they are stored. */
public final class OpaqueTokens {

	/** 256 bits, per DESIGN 11. Too many possibilities to guess, so a fast hash is enough to store it. */
	static final int TOKEN_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private OpaqueTokens() {
	}

	/** URL-safe Base64 without padding: 43 characters that survive JSON, headers and query strings unchanged. */
	public static String generate() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * SHA-256, not BCrypt: a 256-bit random token cannot be brute-forced the way a password can, and the
	 * hash has to be deterministic so the token can be looked up by it. A database leak then exposes
	 * hashes that cannot be replayed as tokens.
	 */
	public static String sha256Hex(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required on every Java platform", ex);
		}
	}

}
