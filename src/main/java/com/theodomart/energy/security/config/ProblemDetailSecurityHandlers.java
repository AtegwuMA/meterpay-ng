package com.theodomart.energy.security.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Failures inside the security filter chain happen before Spring MVC, so the RestControllerAdvice never
 * sees them. Handing them to MVC's exception resolver gives 401 and 403 the same problem detail shape,
 * code and traceId as every other error (DESIGN 10).
 */
final class ProblemDetailSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final HandlerExceptionResolver resolver;

	ProblemDetailSecurityHandlers(HandlerExceptionResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) {
		// RFC 6750: name the scheme, and say when the token that was sent is the problem.
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
				authException instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer");
		resolver.resolveException(request, response, null, authException);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) {
		resolver.resolveException(request, response, null, accessDeniedException);
	}

}
