package com.theodomart.energy.identity.service;

public record RegisterCommand(String email, String phone, String password, String fullName) {

	@Override
	public String toString() {
		// Commands get logged by accident. The password must never be, and the rest is personal data.
		return "RegisterCommand[redacted]";
	}

}
