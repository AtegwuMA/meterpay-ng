package com.theodomart.energy.meter.api;

import com.theodomart.energy.meter.service.MeterService;
import com.theodomart.energy.meter.service.MeterView;
import com.theodomart.energy.meter.service.RegisterMeterCommand;
import com.theodomart.energy.security.service.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meters")
@Validated
public class MeterController {

	private final MeterService meters;

	public MeterController(MeterService meters) {
		this.meters = meters;
	}

	@PostMapping
	@PreAuthorize("hasRole('LANDLORD')")
	public ResponseEntity<MeterView> register(@AuthenticationPrincipal CurrentUser user,
			@Valid @RequestBody RegisterMeterRequest request) {
		MeterView meter = meters.register(user.id(), new RegisterMeterCommand(request.serialNumber(),
				request.gatewayType(), request.externalDeviceId(), request.lowCreditThresholdWh()));
		return ResponseEntity.created(URI.create("/api/v1/meters/" + meter.id())).body(meter);
	}

	@PutMapping("/{meterId}/unit")
	@PreAuthorize("hasRole('LANDLORD')")
	public MeterView assignToUnit(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID meterId,
			@Valid @RequestBody AssignUnitRequest request) {
		return meters.assignToUnit(user.id(), meterId, request.unitId());
	}

	@GetMapping("/{meterId}")
	@PreAuthorize("hasAnyRole('LANDLORD', 'TENANT')")
	public MeterView get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID meterId) {
		return meters.get(user, meterId);
	}

	record RegisterMeterRequest(@NotBlank @Size(max = 40) String serialNumber,
			@NotNull @Pattern(regexp = "SIMULATED|TUYA", message = "must be SIMULATED or TUYA") String gatewayType,
			@Size(max = 100) String externalDeviceId, @PositiveOrZero Long lowCreditThresholdWh) {
	}

	record AssignUnitRequest(@NotNull UUID unitId) {
	}

}
