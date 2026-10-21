package com.theodomart.energy.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.theodomart.energy.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// Full application with Spring Security active, to prove the filter runs before it.
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdIT extends IntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(CorrelationIdIT.class);

	private final MockMvc mvc;

	@Autowired
	CorrelationIdIT(MockMvc mvc) {
		this.mvc = mvc;
	}

	@Test
	void echoesIncomingCorrelationIdOnResponse() throws Exception {
		mvc.perform(get("/actuator/health").header(CorrelationIdFilter.HEADER, "web-9c1e"))
			.andExpect(header().string(CorrelationIdFilter.HEADER, "web-9c1e"));
	}

	@Test
	void requestRejectedBySecurityStillCarriesCorrelationId() throws Exception {
		MvcResult result = mvc.perform(get("/api/v1/wallet"))
			.andExpect(status().isUnauthorized())
			.andReturn();

		String id = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
		assertThat(id).isNotBlank();
		assertThat(UUID.fromString(id)).hasToString(id);
	}

	@Test
	void logLinesIncludeTheCorrelationId(CapturedOutput output) {
		MDC.put(CorrelationIdFilter.MDC_KEY, "probe-4f2a");
		try {
			log.info("correlation probe");
		}
		finally {
			MDC.remove(CorrelationIdFilter.MDC_KEY);
		}

		assertThat(output.getOut()).containsPattern("\\[probe-4f2a\\] .*correlation probe");
	}

}
