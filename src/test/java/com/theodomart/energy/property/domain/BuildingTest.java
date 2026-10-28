package com.theodomart.energy.property.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildingTest {

	private static final Instant NOW = Instant.parse("2026-10-29T09:00:00Z");

	private static final Instant LATER = NOW.plusSeconds(3600);

	private final UUID landlordId = UUID.randomUUID();

	private final Building building = Building.create(landlordId, "  Sunrise Court ", "12 Allen Avenue", "Lagos",
			"Ikeja", NOW);

	@Test
	void newBuildingIsOwnedByItsLandlordWithTrimmedFields() {
		assertThat(building.getId()).isNotNull();
		assertThat(building.getName()).isEqualTo("Sunrise Court");
		assertThat(building.isOwnedBy(landlordId)).isTrue();
		assertThat(building.isOwnedBy(UUID.randomUUID())).isFalse();
		assertThat(building.getCreatedAt()).isEqualTo(NOW).isEqualTo(building.getUpdatedAt());
	}

	@Test
	void blankFieldsAreRejectedOnCreate() {
		assertThatThrownBy(() -> Building.create(landlordId, " ", "a", "Lagos", "Ikeja", NOW))
			.hasMessageContaining("name");
		assertThatThrownBy(() -> Building.create(landlordId, "n", "a", "Lagos", "", NOW)).hasMessageContaining("lga");
		assertThatThrownBy(() -> Building.create(null, "n", "a", "Lagos", "Ikeja", NOW))
			.hasMessageContaining("landlordId");
	}

	@Test
	void partialUpdateChangesOnlyTheGivenFields() {
		building.update("Sunset Court", null, null, null, LATER);

		assertThat(building.getName()).isEqualTo("Sunset Court");
		assertThat(building.getAddress()).isEqualTo("12 Allen Avenue");
		assertThat(building.getState()).isEqualTo("Lagos");
		assertThat(building.getLga()).isEqualTo("Ikeja");
		assertThat(building.getUpdatedAt()).isEqualTo(LATER);
	}

	@Test
	void updateWithNoRealChangeKeepsUpdatedAt() {
		building.update("Sunrise Court", null, "Lagos", null, LATER);

		assertThat(building.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	void blankValueInAnUpdateIsRejectedAndNothingChanges() {
		assertThatThrownBy(() -> building.update("New name", " ", null, null, LATER))
			.hasMessageContaining("address");

		assertThat(building.getName()).as("all or nothing").isEqualTo("Sunrise Court");
	}

}
