package com.theodomart.energy.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

	@Test
	void generatesUrlSafeTokensCarrying256RandomBits() {
		String token = OpaqueTokens.generate();

		assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
		assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
	}

	@Test
	void generatesADifferentTokenEveryTime() {
		Set<String> tokens = new HashSet<>();
		for (int i = 0; i < 1_000; i++) {
			tokens.add(OpaqueTokens.generate());
		}

		assertThat(tokens).hasSize(1_000);
	}

	@Test
	void hashesWithSha256AsLowerCaseHex() {
		// Known test vector from FIPS 180-2.
		assertThat(OpaqueTokens.sha256Hex("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	void hashIsDeterministicSoTokensCanBeLookedUpByIt() {
		String token = OpaqueTokens.generate();

		assertThat(OpaqueTokens.sha256Hex(token)).isEqualTo(OpaqueTokens.sha256Hex(token)).doesNotContain(token);
	}

}
