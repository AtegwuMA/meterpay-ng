package com.theodomart.energy.meter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "meters")
public class Meter {

	private static final Pattern SERIAL = Pattern.compile("[A-Z0-9-]{4,40}");

	@Id
	private UUID id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private UUID landlordId;

	@Column(name = "unit_id")
	private UUID unitId;

	@Column(name = "serial_number", nullable = false, updatable = false)
	private String serialNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "gateway_type", nullable = false, updatable = false)
	private GatewayType gatewayType;

	@Column(name = "external_device_id", updatable = false)
	private String externalDeviceId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MeterStatus status;

	@Column(name = "last_register_wh", nullable = false)
	private long lastRegisterWh;

	@Column(name = "last_reading_at")
	private Instant lastReadingAt;

	@Column(name = "credit_balance_wh", nullable = false)
	private long creditBalanceWh;

	@Column(name = "low_credit_threshold_wh", nullable = false)
	private long lowCreditThresholdWh;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Meter() {
		// for JPA
	}

	private Meter(UUID landlordId, String serialNumber, GatewayType gatewayType, String externalDeviceId,
			long lowCreditThresholdWh, Instant now) {
		this.id = UUID.randomUUID();
		this.landlordId = Objects.requireNonNull(landlordId, "landlordId");
		this.serialNumber = normaliseSerial(serialNumber);
		this.gatewayType = Objects.requireNonNull(gatewayType, "gatewayType");
		this.externalDeviceId = externalDeviceId == null || externalDeviceId.isBlank() ? null : externalDeviceId.strip();
		if (gatewayType == GatewayType.TUYA && this.externalDeviceId == null) {
			throw new IllegalArgumentException("A Tuya meter needs an external device id");
		}
		if (lowCreditThresholdWh < 0) {
			throw new IllegalArgumentException("Low credit threshold cannot be negative");
		}
		this.lowCreditThresholdWh = lowCreditThresholdWh;
		this.status = MeterStatus.PROVISIONED;
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}

	public static Meter register(UUID landlordId, String serialNumber, GatewayType gatewayType,
			String externalDeviceId, long lowCreditThresholdWh, Instant now) {
		return new Meter(landlordId, serialNumber, gatewayType, externalDeviceId, lowCreditThresholdWh, now);
	}

	/** Serials are printed on hardware and retyped by hand, so case must not make two entries for one device. */
	private static String normaliseSerial(String serialNumber) {
		String value = serialNumber == null ? "" : serialNumber.strip().toUpperCase(Locale.ROOT);
		if (!SERIAL.matcher(value).matches()) {
			throw new IllegalArgumentException("Serial number must be 4 to 40 letters, digits or dashes");
		}
		return value;
	}

	/**
	 * Installs the meter on a unit. Repeating it for the same unit changes nothing, so a client retry is safe.
	 *
	 * @throws IllegalStateException if it is already installed elsewhere: its credit and readings belong to that
	 * unit's tenant, so moving a meter needs its own decommissioning flow
	 */
	public void assignToUnit(UUID unitId, Instant now) {
		Objects.requireNonNull(unitId, "unitId");
		if (unitId.equals(this.unitId)) {
			return;
		}
		if (this.unitId != null) {
			throw new IllegalStateException("Meter is already assigned to another unit");
		}
		this.unitId = unitId;
		this.updatedAt = Objects.requireNonNull(now, "now");
	}

	public UUID getId() {
		return id;
	}

	public UUID getLandlordId() {
		return landlordId;
	}

	public UUID getUnitId() {
		return unitId;
	}

	public String getSerialNumber() {
		return serialNumber;
	}

	public GatewayType getGatewayType() {
		return gatewayType;
	}

	public String getExternalDeviceId() {
		return externalDeviceId;
	}

	public MeterStatus getStatus() {
		return status;
	}

	public long getLastRegisterWh() {
		return lastRegisterWh;
	}

	public Instant getLastReadingAt() {
		return lastReadingAt;
	}

	public long getCreditBalanceWh() {
		return creditBalanceWh;
	}

	public long getLowCreditThresholdWh() {
		return lowCreditThresholdWh;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof Meter other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "Meter[id=" + id + ", status=" + status + "]";
	}

}
