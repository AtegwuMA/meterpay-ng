package com.theodomart.energy.property.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class InviteCodeTest {

	@RepeatedTest(20)
	void generatesTenCharactersWithoutLookAlikes() {
		assertThat(InviteCode.generate()).matches("[2-9A-HJKMNP-TV-Z]{5}-[2-9A-HJKMNP-TV-Z]{5}");
	}

	@Test
	void generatesDifferentCodes() {
		Set<String> codes = new HashSet<>();
		for (int i = 0; i < 1_000; i++) {
			codes.add(InviteCode.generate());
		}

		assertThat(codes).hasSize(1_000);
	}

	@Test
	void hashIgnoresCaseSpacesAndDashes() {
		String expected = InviteCode.hash("K7MPQ-2XR9T");

		assertThat(InviteCode.hash("k7mpq2xr9t")).isEqualTo(expected);
		assertThat(InviteCode.hash(" K7MPQ 2XR9T ")).isEqualTo(expected);
		assertThat(InviteCode.hash("K7MPQ-2XR9X")).isNotEqualTo(expected);
	}

	@Test
	void hashIsSha256HexAndDoesNotContainTheCode() {
		assertThat(InviteCode.hash("K7MPQ-2XR9T")).matches("[0-9a-f]{64}").doesNotContain("K7MPQ");
	}

}
