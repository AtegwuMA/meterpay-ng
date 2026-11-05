package com.theodomart.energy.meter.service;

import java.time.Instant;
import java.util.UUID;

/** Status and gateway are strings because other modules read this view and may not depend on meter.domain. */
public record MeterView(UUID id, String serialNumber, String gatewayType, UUID unitId, String status,
		long lastRegisterWh, Instant lastReadingAt, long creditBalanceWh, long lowCreditThresholdWh,
		Instant createdAt) {

}
