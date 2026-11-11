package com.theodomart.energy.property.api;

import com.theodomart.energy.property.service.TenancyService;
import com.theodomart.energy.property.service.TenancyView;
import com.theodomart.energy.security.service.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenancies")
@Validated
public class TenancyController {

	private final TenancyService tenancies;

	public TenancyController(TenancyService tenancies) {
		this.tenancies = tenancies;
	}

	/** For a tenant who already has an account. The tenant comes from the token, never from the request. */
	@PostMapping("/redeem")
	@PreAuthorize("hasRole('TENANT')")
	@ResponseStatus(HttpStatus.CREATED)
	public TenancyView redeem(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody RedeemRequest request) {
		return tenancies.joinUnit(user.id(), request.inviteCode());
	}

	// Same limits as the code on registration, so a code that works in one place works in the other.
	record RedeemRequest(@NotBlank @Size(min = 10, max = 32) String inviteCode) {
	}

}
