package com.theodomart.energy.common.domain;

/**
 * Who a user is to the system. Lives in common, not identity, because every module that authorises a
 * request needs it, and ArchUnit rule 2 forbids reaching into identity's domain package.
 */
public enum Role {

	LANDLORD, TENANT, ADMIN

}
