package com.theodomart.energy.meter.service;

/** {@code lowCreditThresholdWh} may be null for the default. {@code gatewayType} is SIMULATED or TUYA. */
public record RegisterMeterCommand(String serialNumber, String gatewayType, String externalDeviceId,
		Long lowCreditThresholdWh) {

}
