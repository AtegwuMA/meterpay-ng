package com.theodomart.energy.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			08031234567        | +2348031234567
			0803 123 4567      | +2348031234567
			0803-123-4567      | +2348031234567
			(0803) 123.4567    | +2348031234567
			2348031234567      | +2348031234567
			+2348031234567     | +2348031234567
			+234 803 123 4567  | +2348031234567
			+447911123456      | +447911123456
			""")
	void normalisesCommonNigerianFormatsToE164(String raw, String expected) {
		assertThat(PhoneNumbers.toE164(raw)).isEqualTo(expected);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "0803123456", "080312345678", "8031234567", "+0348031234567", "phone", "+1234567" })
	void rejectsNumbersThatFitNoKnownForm(String raw) {
		assertThatThrownBy(() -> PhoneNumbers.toE164(raw)).isInstanceOf(IllegalArgumentException.class);
	}

}
