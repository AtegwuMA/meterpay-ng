package com.theodomart.energy.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception to an RFC 9457 problem detail with a stable {@code code} and a {@code traceId}
 * (DESIGN §10). Internal messages and stack traces never reach the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	/** MDC key populated by the correlation ID filter (M0-07). */
	public static final String TRACE_ID_MDC_KEY = "correlationId";

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(DomainException.class)
	ResponseEntity<Object> handleDomain(DomainException ex, HttpServletRequest request) {
		ErrorCode code = ex.errorCode();
		String traceId = traceId();
		log.info("Request rejected: code={} traceId={}", code, traceId);
		return problem(code, ex.getMessage(), request, traceId);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
		String traceId = traceId();
		log.warn("Concurrent modification: traceId={}", traceId);
		return problem(ErrorCode.CONCURRENT_MODIFICATION,
				"The resource was modified by another request. Retry with fresh data.", request, traceId);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
		List<FieldError> errors = ex.getConstraintViolations()
			.stream()
			.map(v -> new FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
			.sorted(Comparator.comparing(FieldError::field))
			.toList();
		ResponseEntity<Object> response = problem(ErrorCode.VALIDATION_FAILED, "Request validation failed.", request,
				traceId());
		((ProblemDetail) response.getBody()).setProperty("errors", errors);
		return response;
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
		return problem(ErrorCode.UNAUTHENTICATED, "Authentication is required to access this resource.", request,
				traceId());
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
		return problem(ErrorCode.FORBIDDEN, "You do not have permission to perform this action.", request, traceId());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
		String traceId = traceId();
		log.error("Unhandled exception: traceId={}", traceId, ex);
		return problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred. Quote the traceId if you contact support.",
				request, traceId);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldError> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
			.sorted(Comparator.comparing(FieldError::field))
			.toList();
		ProblemDetail body = ex.getBody();
		body.setDetail("Request validation failed.");
		body.setProperty("errors", errors);
		return handleExceptionInternal(ex, body, headers, status, request);
	}

	/** Every response Spring MVC builds itself passes through here, so it gets the same shape. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ProblemDetail problem = body instanceof ProblemDetail detail ? detail
				: ProblemDetail.forStatus(statusCode);
		ErrorCode code = ErrorCode.forFrameworkStatus(statusCode.value());
		problem.setType(code.type());
		problem.setTitle(code.title());
		if (request instanceof ServletWebRequest servletRequest) {
			problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
		}
		problem.setProperty("code", code.name());
		problem.setProperty("traceId", traceId());
		return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
	}

	private ResponseEntity<Object> problem(ErrorCode code, String detail, HttpServletRequest request, String traceId) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
		problem.setType(code.type());
		problem.setTitle(code.title());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code.name());
		problem.setProperty("traceId", traceId);
		return ResponseEntity.status(code.status()).body(problem);
	}

	// Until the correlation filter exists (M0-07) there may be nothing in the MDC. A generated ID still
	// ties the response to the server log line for that error.
	private static String traceId() {
		String fromMdc = MDC.get(TRACE_ID_MDC_KEY);
		return fromMdc != null ? fromMdc : UUID.randomUUID().toString();
	}

	private static String lastNode(String propertyPath) {
		int dot = propertyPath.lastIndexOf('.');
		return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
	}

	public record FieldError(String field, String message) {
	}

}
