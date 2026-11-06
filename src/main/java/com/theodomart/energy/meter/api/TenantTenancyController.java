package com.theodomart.energy.meter.api;

import com.theodomart.energy.meter.service.MeterService;
import com.theodomart.energy.meter.service.TenantTenancyView;
import com.theodomart.energy.security.service.CurrentUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** In the meter module, not identity: meter already depends on property, and the reverse would be a cycle (DESIGN 17). */
@RestController
@RequestMapping("/api/v1/me/tenancies")
@Validated
public class TenantTenancyController {

	private final MeterService meters;

	public TenantTenancyController(MeterService meters) {
		this.meters = meters;
	}

	@GetMapping
	@PreAuthorize("hasRole('TENANT')")
	public List<TenantTenancyView> list(@AuthenticationPrincipal CurrentUser user) {
		return meters.listTenancies(user.id());
	}

}
