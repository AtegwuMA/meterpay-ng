package com.theodomart.energy.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {

	@Override
	public String toString() {
		return "RefreshRequest[redacted]";
	}

}
