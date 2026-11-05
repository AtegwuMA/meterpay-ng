package com.theodomart.energy.meter.domain;

/**
 * The states of DESIGN 7.2, and the only place that says which moves between them are allowed.
 *
 * <p>Returning from TAMPER_SUSPECTED or SUSPENDED goes back to the previous state. This table allows every
 * state that return could land on; choosing the actual previous one is the caller's job.
 */
public enum MeterStatus {

	PROVISIONED, ACTIVE, LOW_CREDIT, CUTOFF, TAMPER_SUSPECTED, SUSPENDED, DECOMMISSIONED;

	public boolean canTransitionTo(MeterStatus target) {
		// Written as an exhaustive switch so a new state will not compile until its moves are decided.
		return switch (this) {
			case PROVISIONED -> target == ACTIVE || target == SUSPENDED || target == DECOMMISSIONED;
			case ACTIVE -> target == LOW_CREDIT || target == CUTOFF || target == TAMPER_SUSPECTED
					|| target == SUSPENDED || target == DECOMMISSIONED;
			case LOW_CREDIT -> target == ACTIVE || target == CUTOFF || target == TAMPER_SUSPECTED
					|| target == SUSPENDED || target == DECOMMISSIONED;
			case CUTOFF -> target == ACTIVE || target == TAMPER_SUSPECTED || target == SUSPENDED
					|| target == DECOMMISSIONED;
			case TAMPER_SUSPECTED -> target == ACTIVE || target == LOW_CREDIT || target == CUTOFF
					|| target == SUSPENDED || target == DECOMMISSIONED;
			case SUSPENDED -> target == PROVISIONED || target == ACTIVE || target == LOW_CREDIT || target == CUTOFF
					|| target == TAMPER_SUSPECTED || target == DECOMMISSIONED;
			case DECOMMISSIONED -> false;
		};
	}

}
