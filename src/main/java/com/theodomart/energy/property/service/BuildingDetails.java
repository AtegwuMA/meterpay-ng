package com.theodomart.energy.property.service;

/** Building fields for a create (all required) or an update (null means unchanged). */
public record BuildingDetails(String name, String address, String state, String lga) {

}
