package com.theodomart.energy.property.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnitTest {

	private static final Instant NOW = Instant.parse("2026-10-29T09:00:00Z");

	@Test
	void labelIsTrimmedAndInnerSpacesCollapsed() {
		Unit unit = Unit.create(UUID.randomUUID(), "  Flat   3B ", NOW);

		assertThat(unit.getLabel()).isEqualTo("Flat 3B");
		assertThat(unit.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void blankLabelOrMissingBuildingIsRejected() {
		assertThatThrownBy(() -> Unit.create(UUID.randomUUID(), "  ", NOW)).hasMessageContaining("label");
		assertThatThrownBy(() -> Unit.create(null, "Flat 1", NOW)).hasMessageContaining("buildingId");
	}

	@Test
	void equalityIsById() {
		UUID building = UUID.randomUUID();
		Unit a = Unit.create(building, "Flat 1", NOW);

		assertThat(a).isEqualTo(a).hasSameHashCodeAs(a).isNotEqualTo(Unit.create(building, "Flat 1", NOW));
	}

}
