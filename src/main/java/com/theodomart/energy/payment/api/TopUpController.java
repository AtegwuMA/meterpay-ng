package com.theodomart.energy.payment.api;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.payment.service.TopUpService;
import com.theodomart.energy.payment.service.TopUpService.StartedTopUp;
import com.theodomart.energy.payment.service.TopUpView;
import com.theodomart.energy.security.service.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet/top-ups")
@PreAuthorize("hasRole('TENANT')")
@Validated
public class TopUpController {

	/** ₦100: small top-ups cost the platform a gateway fee out of all proportion to the amount. */
	static final long MIN_KOBO = 10_000;

	/** ₦1,000,000: well beyond any month of electricity for a flat, and a cap on what one mistake can move. */
	static final long MAX_KOBO = 100_000_000;

	private final TopUpService topUps;

	public TopUpController(TopUpService topUps) {
		this.topUps = topUps;
	}

	/** 201 for a new top-up; 200 with the same body when the key was used before for the same amount. */
	@PostMapping
	public ResponseEntity<TopUpView> start(@AuthenticationPrincipal CurrentUser user,
			@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody TopUpRequest request) {
		StartedTopUp started = topUps.start(user.id(), idempotencyKey, Money.ofKobo(request.amountKobo()));
		return ResponseEntity.status(started.created() ? HttpStatus.CREATED : HttpStatus.OK).body(started.topUp());
	}

	/** Owner only: another tenant's top-up is a 404. Asks Paystack first while the payment is still pending. */
	@GetMapping("/{intentId}")
	public TopUpView status(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID intentId) {
		return topUps.status(user.id(), intentId);
	}

	record TopUpRequest(@NotNull @Min(MIN_KOBO) @Max(MAX_KOBO) Long amountKobo) {
	}

}
