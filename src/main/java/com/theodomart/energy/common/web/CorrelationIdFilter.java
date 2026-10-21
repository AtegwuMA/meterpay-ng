package com.theodomart.energy.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts or generates {@code X-Correlation-Id}, puts it in the MDC for every log line of the request,
 * and echoes it on the response (DESIGN §12).
 */
// Highest precedence so the ID also exists for requests that Spring Security rejects.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Correlation-Id";

	public static final String MDC_KEY = "correlationId";

	// The value is client controlled and written into every log line. Restricting it to short,
	// plain tokens stops log injection (newlines, fake log entries) and oversized values.
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String correlationId = resolve(request.getHeader(HEADER));
		MDC.put(MDC_KEY, correlationId);
		// Set before the chain runs: once the body starts streaming, headers can no longer change.
		response.setHeader(HEADER, correlationId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			// Servlet threads are pooled. Without this the next request on the thread inherits the ID.
			MDC.remove(MDC_KEY);
		}
	}

	private static String resolve(String incoming) {
		if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
			return incoming;
		}
		return UUID.randomUUID().toString();
	}

}
