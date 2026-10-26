package com.theodomart.energy.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// No password length rules here: login must not reveal the password policy, only "right or wrong".
public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 256) String password) {

	@Override
	public String toString() {
		return "LoginRequest[redacted]";
	}

}
