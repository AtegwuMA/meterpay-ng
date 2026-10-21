package com.theodomart.energy.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

	private final CorrelationIdFilter filter = new CorrelationIdFilter();

	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/wallet");

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	private final AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

	private final FilterChain recordingChain = (req, res) -> mdcDuringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY));

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void reusesIncomingCorrelationId() throws Exception {
		request.addHeader(CorrelationIdFilter.HEADER, "mobile-7f3a.42");

		filter.doFilter(request, response, recordingChain);

		assertThat(mdcDuringRequest.get()).isEqualTo("mobile-7f3a.42");
		assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("mobile-7f3a.42");
	}

	@Test
	void generatesCorrelationIdWhenHeaderIsMissing() throws Exception {
		filter.doFilter(request, response, recordingChain);

		String generated = response.getHeader(CorrelationIdFilter.HEADER);
		assertThat(generated).isNotBlank();
		assertThat(UUID.fromString(generated)).hasToString(generated);
		assertThat(mdcDuringRequest.get()).isEqualTo(generated);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "abc\n2026-10-21 INFO fake log line", "id with spaces", "<script>",
			"0123456789012345678901234567890123456789012345678901234567890123456789" })
	void replacesUnsafeIncomingIdWithAGeneratedOne(String unsafe) throws Exception {
		request.addHeader(CorrelationIdFilter.HEADER, unsafe);

		filter.doFilter(request, response, recordingChain);

		String used = response.getHeader(CorrelationIdFilter.HEADER);
		assertThat(used).isNotEqualTo(unsafe);
		assertThat(UUID.fromString(used)).hasToString(used);
		assertThat(mdcDuringRequest.get()).isEqualTo(used);
	}

	@Test
	void removesIdFromMdcAfterTheRequest() throws Exception {
		filter.doFilter(request, response, recordingChain);

		assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void removesIdFromMdcEvenWhenTheRequestFails() {
		FilterChain failingChain = (req, res) -> {
			throw new IllegalStateException("handler blew up");
		};

		assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
			.isInstanceOf(IllegalStateException.class);
		assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
	}

}
