package com.theodomart.energy.security.serviceImpl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.common.error.ResourceNotFoundException;
import com.theodomart.energy.property.service.BuildingOwnerLookup;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnershipServiceImplTest {

	private final UUID landlord = UUID.randomUUID();

	private final UUID building = UUID.randomUUID();

	private final UUID unit = UUID.randomUUID();

	private final OwnershipServiceImpl ownership = new OwnershipServiceImpl(new BuildingOwnerLookup() {
		@Override
		public Optional<UUID> landlordOf(UUID id) {
			return id.equals(building) ? Optional.of(landlord) : Optional.empty();
		}

		@Override
		public Optional<UUID> landlordOfUnit(UUID id) {
			return id.equals(unit) ? Optional.of(landlord) : Optional.empty();
		}
	});

	@Test
	void ownerPasses() {
		assertThatCode(() -> ownership.requireBuildingOwner(landlord, building)).doesNotThrowAnyException();
	}

	@Test
	void anotherUserIsToldTheBuildingDoesNotExist() {
		assertThatThrownBy(() -> ownership.requireBuildingOwner(UUID.randomUUID(), building))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessage("Building " + building + " was not found.");
	}

	@Test
	void unknownBuildingGetsTheSameAnswerAsSomeoneElsesBuilding() {
		UUID unknown = UUID.randomUUID();

		assertThatThrownBy(() -> ownership.requireBuildingOwner(landlord, unknown))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessage("Building " + unknown + " was not found.");
	}

	@Test
	void unitOwnerPasses() {
		assertThatCode(() -> ownership.requireUnitOwner(landlord, unit)).doesNotThrowAnyException();
	}

	@Test
	void someoneElsesUnitAndAnUnknownUnitGetTheSameAnswer() {
		UUID unknown = UUID.randomUUID();

		assertThatThrownBy(() -> ownership.requireUnitOwner(UUID.randomUUID(), unit))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessage("Unit " + unit + " was not found.");
		assertThatThrownBy(() -> ownership.requireUnitOwner(landlord, unknown))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessage("Unit " + unknown + " was not found.");
	}

}
