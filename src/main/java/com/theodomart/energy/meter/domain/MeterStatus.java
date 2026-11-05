package com.theodomart.energy.meter.domain;

/** The states of DESIGN 7.2. Which moves between them are allowed is added with the state machine in M2-02. */
public enum MeterStatus {

	PROVISIONED, ACTIVE, LOW_CREDIT, CUTOFF, TAMPER_SUSPECTED, SUSPENDED, DECOMMISSIONED

}
