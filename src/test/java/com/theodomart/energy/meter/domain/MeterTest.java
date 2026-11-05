package com.theodomart.energy.meter.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MeterTest {

	private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

	private final UUID landlord = UUID.randomUUID();

	private final Meter meter = Meter.register(landlord, " sim-001 ", GatewayType.SIMULATED, null, 5_000, NOW);

	@Test
	void newMeterIsProvisionedUnassignedAndEmpty() {
		assertThat(meter.getId()).isNotNull();
		assertThat(meter.getLandlordId()).isEqualTo(landlord);
		assertThat(meter.getStatus()).isEqualTo(MeterStatus.PROVISIONED);
		assertThat(meter.getUnitId()).isNull();
		assertThat(meter.getCreditBalanceWh()).isZero();
		assertThat(meter.getLastRegisterWh()).isZero();
		assertThat(meter.getLastReadingAt()).isNull();
		assertThat(meter.getLowCreditThresholdWh()).isEqualTo(5_000);
	}

	@Test
	void serialIsTrimmedAndUpperCased() {
		assertThat(meter.getSerialNumber()).isEqualTo("SIM-001");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "abc", "has space", "under_score", "0123456789012345678901234567890123456789X" })
	void badSerialIsRejected(String serial) {
		assertThatThrownBy(() -> Meter.register(landlord, serial, GatewayType.SIMULATED, null, 0, NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Serial");
	}

	@Test
	void tuyaMeterNeedsADeviceId() {
		assertThatThrownBy(() -> Meter.register(landlord, "TUYA-1", GatewayType.TUYA, " ", 0, NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("device id");
		assertThat(Meter.register(landlord, "TUYA-1", GatewayType.TUYA, " dev-1 ", 0, NOW).getExternalDeviceId())
			.isEqualTo("dev-1");
	}

	@Test
	void negativeThresholdIsRejected() {
		assertThatThrownBy(() -> Meter.register(landlord, "SIM-2", GatewayType.SIMULATED, null, -1, NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void assigningIsRepeatableForTheSameUnitButNotAnotherOne() {
		UUID unit = UUID.randomUUID();

		meter.assignToUnit(unit, NOW);
		meter.assignToUnit(unit, NOW);

		assertThat(meter.getUnitId()).isEqualTo(unit);
		assertThatThrownBy(() -> meter.assignToUnit(UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
		assertThat(meter.getUnitId()).isEqualTo(unit);
	}

	@Test
	void assigningDoesNotActivateTheMeter() {
		meter.assignToUnit(UUID.randomUUID(), NOW);

		assertThat(meter.getStatus()).as("DESIGN 7.2 needs a first reading as well").isEqualTo(MeterStatus.PROVISIONED);
	}

}
