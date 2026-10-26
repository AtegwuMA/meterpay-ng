package com.theodomart.energy.common.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.theodomart.energy.common.web.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Slice test: only the MVC layer and this advice are loaded, no database or Docker.
// Security filters are off because this test is about error bodies, not authentication.
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest {

	private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

	private final MockMvc mvc;

	@Autowired
	GlobalExceptionHandlerTest(MockMvc mvc) {
		this.mvc = mvc;
	}

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void invalidBodyReturns400WithFieldErrors() throws Exception {
		mvc.perform(post("/test/purchases").contentType(MediaType.APPLICATION_JSON).content("""
				{"meterId": "", "amountKobo": -5}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://meterpay.theodomart.com/problems/validation-failed"))
			.andExpect(jsonPath("$.title").value("Validation failed"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.instance").value("/test/purchases"))
			.andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())))
			.andExpect(jsonPath("$.errors.length()").value(2))
			.andExpect(jsonPath("$.errors[0].field").value("amountKobo"))
			.andExpect(jsonPath("$.errors[1].field").value("meterId"));
	}

	@Test
	void invalidRequestParamReturns400WithFieldErrors() throws Exception {
		mvc.perform(get("/test/readings").param("days", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("days"));
	}

	@Test
	void malformedJsonReturns400WithoutParserInternals() throws Exception {
		mvc.perform(post("/test/purchases").contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(content().string(not(containsString("jackson"))));
	}

	@Test
	void domainNotFoundReturns404() throws Exception {
		mvc.perform(get("/test/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://meterpay.theodomart.com/problems/not-found"))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.detail").value("Building 42 was not found"))
			.andExpect(jsonPath("$.instance").value("/test/not-found"));
	}

	@Test
	void unknownRouteReturns404ProblemDetail() throws Exception {
		mvc.perform(get("/test/does-not-exist"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));
	}

	@Test
	void wrongMethodReturns405ProblemDetail() throws Exception {
		mvc.perform(post("/test/not-found"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void illegalTransitionReturns409() throws Exception {
		mvc.perform(post("/test/transition"))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"))
			.andExpect(jsonPath("$.title").value("Illegal state transition"))
			.andExpect(jsonPath("$.detail").value("Cannot move meter from DECOMMISSIONED to ACTIVE"));
	}

	@Test
	void optimisticLockFailureReturns409ConcurrentModification() throws Exception {
		mvc.perform(post("/test/concurrent"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
			.andExpect(content().string(not(containsString("LedgerAccount"))));
	}

	@Test
	void businessRuleViolationReturns422WithItsDetail() throws Exception {
		mvc.perform(post("/test/purchase-too-big"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.type").value("https://meterpay.theodomart.com/problems/insufficient-wallet-balance"))
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_WALLET_BALANCE"))
			.andExpect(jsonPath("$.detail")
				.value("Wallet balance is ₦1,200.00 but the purchase requires ₦2,000.00"));
	}

	@Test
	void unexpectedErrorReturns500WithoutLeakingInternals() throws Exception {
		mvc.perform(get("/test/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())))
			.andExpect(content().string(not(containsString("s3cret"))))
			.andExpect(content().string(not(containsString("IllegalStateException"))))
			.andExpect(content().string(not(containsString("at com.theodomart"))));
	}

	@Test
	void traceIdComesFromCorrelationIdInMdc() throws Exception {
		MDC.put(CorrelationIdFilter.MDC_KEY, "a1b2c3d4");

		mvc.perform(get("/test/boom")).andExpect(jsonPath("$.traceId").value("a1b2c3d4"));
		mvc.perform(get("/test/does-not-exist")).andExpect(jsonPath("$.traceId").value("a1b2c3d4"));
	}

	record PurchaseRequest(@NotBlank String meterId, @Positive long amountKobo) {
	}

	static final class TestNotFoundException extends DomainException {

		TestNotFoundException(String detail) {
			super(ErrorCode.NOT_FOUND, detail);
		}

	}

	static final class TestTransitionException extends DomainException {

		TestTransitionException(String detail) {
			super(ErrorCode.ILLEGAL_STATE_TRANSITION, detail);
		}

	}

	static final class TestInsufficientBalanceException extends DomainException {

		TestInsufficientBalanceException(String detail) {
			super(ErrorCode.INSUFFICIENT_WALLET_BALANCE, detail);
		}

	}

	@RestController
	@Validated
	static class ThrowingController {

		@PostMapping("/test/purchases")
		String purchase(@Valid @RequestBody PurchaseRequest request) {
			return "ok";
		}

		@GetMapping("/test/readings")
		String readings(@RequestParam @Positive int days) {
			return "ok";
		}

		@GetMapping("/test/not-found")
		String notFound() {
			throw new TestNotFoundException("Building 42 was not found");
		}

		@PostMapping("/test/transition")
		String transition() {
			throw new TestTransitionException("Cannot move meter from DECOMMISSIONED to ACTIVE");
		}

		@PostMapping("/test/concurrent")
		String concurrent() {
			throw new ObjectOptimisticLockingFailureException("LedgerAccount", "6f1c");
		}

		@PostMapping("/test/purchase-too-big")
		String purchaseTooBig() {
			throw new TestInsufficientBalanceException(
					"Wallet balance is ₦1,200.00 but the purchase requires ₦2,000.00");
		}

		@GetMapping("/test/boom")
		String boom() {
			throw new IllegalStateException("db password s3cret leaked in message");
		}

	}

}
