package com.theodomart.energy.tariff.api;

import com.theodomart.energy.common.web.PageResponse;
import com.theodomart.energy.security.service.CurrentUser;
import com.theodomart.energy.tariff.service.NewTariffVersion;
import com.theodomart.energy.tariff.service.TariffService;
import com.theodomart.energy.tariff.service.TariffVersionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/buildings/{buildingId}/tariffs")
@PreAuthorize("hasRole('LANDLORD')")
@Validated
public class TariffController {

	private final TariffService tariffs;

	public TariffController(TariffService tariffs) {
		this.tariffs = tariffs;
	}

	@PostMapping
	public ResponseEntity<TariffVersionView> addVersion(@AuthenticationPrincipal CurrentUser user,
			@PathVariable UUID buildingId, @Valid @RequestBody NewVersionRequest request) {
		TariffVersionView version = tariffs.addVersion(user.id(), buildingId,
				new NewTariffVersion(request.rateKoboPerKwh(), request.effectiveFrom(), request.planName()));
		return ResponseEntity.created(URI.create("/api/v1/buildings/" + buildingId + "/tariffs/" + version.id()))
			.body(version);
	}

	@GetMapping
	public PageResponse<TariffVersionView> listVersions(@AuthenticationPrincipal CurrentUser user,
			@PathVariable UUID buildingId, @RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return PageResponse.from(tariffs.listVersions(user.id(), buildingId, page, size), v -> v);
	}

	record NewVersionRequest(@NotNull @Positive Long rateKoboPerKwh, Instant effectiveFrom,
			@Size(max = 120) String planName) {
	}

}
