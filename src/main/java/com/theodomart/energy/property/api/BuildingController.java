package com.theodomart.energy.property.api;

import com.theodomart.energy.common.web.PageResponse;
import com.theodomart.energy.property.service.BuildingDetails;
import com.theodomart.energy.property.service.BuildingView;
import com.theodomart.energy.property.service.PropertyService;
import com.theodomart.energy.property.service.UnitView;
import com.theodomart.energy.security.service.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/buildings")
@PreAuthorize("hasRole('LANDLORD')")
@Validated
public class BuildingController {

	private final PropertyService properties;

	public BuildingController(PropertyService properties) {
		this.properties = properties;
	}

	@PostMapping
	public ResponseEntity<BuildingView> create(@AuthenticationPrincipal CurrentUser user,
			@Valid @RequestBody BuildingRequests.CreateBuilding request) {
		BuildingView building = properties.createBuilding(user.id(),
				new BuildingDetails(request.name(), request.address(), request.state(), request.lga()));
		return ResponseEntity.created(URI.create("/api/v1/buildings/" + building.id())).body(building);
	}

	@GetMapping
	public PageResponse<BuildingView> list(@AuthenticationPrincipal CurrentUser user,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return PageResponse.from(properties.listBuildings(user.id(), page, size), b -> b);
	}

	@GetMapping("/{buildingId}")
	public BuildingView get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID buildingId) {
		return properties.getBuilding(user.id(), buildingId);
	}

	@PatchMapping("/{buildingId}")
	public BuildingView update(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID buildingId,
			@Valid @RequestBody BuildingRequests.UpdateBuilding request) {
		return properties.updateBuilding(user.id(), buildingId,
				new BuildingDetails(request.name(), request.address(), request.state(), request.lga()));
	}

	@PostMapping("/{buildingId}/units")
	public ResponseEntity<UnitView> addUnit(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID buildingId,
			@Valid @RequestBody BuildingRequests.CreateUnit request) {
		UnitView unit = properties.addUnit(user.id(), buildingId, request.label());
		return ResponseEntity.created(URI.create("/api/v1/buildings/" + buildingId + "/units/" + unit.id()))
			.body(unit);
	}

	@GetMapping("/{buildingId}/units")
	public PageResponse<UnitView> listUnits(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID buildingId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
		return PageResponse.from(properties.listUnits(user.id(), buildingId, page, size), u -> u);
	}

}
