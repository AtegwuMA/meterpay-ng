package com.theodomart.energy.property.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies for the building and unit endpoints. */
final class BuildingRequests {

	private BuildingRequests() {
	}

	record CreateBuilding(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 300) String address,
			@NotBlank @Size(max = 60) String state, @NotBlank @Size(max = 100) String lga) {
	}

	/** PATCH: every field optional. A present field must not be blank (checked by the entity). */
	record UpdateBuilding(@Size(max = 120) String name, @Size(max = 300) String address,
			@Size(max = 60) String state, @Size(max = 100) String lga) {
	}

	record CreateUnit(@NotBlank @Size(max = 50) String label) {
	}

}
