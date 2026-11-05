package com.theodomart.energy.property.api;

import com.theodomart.energy.property.service.InviteView;
import com.theodomart.energy.property.service.TenancyService;
import com.theodomart.energy.property.service.TenancyView;
import com.theodomart.energy.security.service.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/units/{unitId}")
@PreAuthorize("hasRole('LANDLORD')")
@Validated
public class UnitController {

	private final TenancyService tenancies;

	public UnitController(TenancyService tenancies) {
		this.tenancies = tenancies;
	}

	/** The response is the only time the code is shown: only its hash is kept. */
	@PostMapping("/invites")
	@ResponseStatus(HttpStatus.CREATED)
	public InviteView createInvite(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID unitId) {
		return tenancies.createInvite(user.id(), unitId);
	}

	@PostMapping("/tenancy/end")
	public TenancyView endTenancy(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID unitId) {
		return tenancies.endTenancy(user.id(), unitId);
	}

}
