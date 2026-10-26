package com.theodomart.energy.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(max = 32) String phone,
		// Max 72: BCrypt ignores anything past 72 bytes. The service also checks bytes, since one character can
		// take up to 4 bytes in UTF-8.
		@NotBlank @Size(min = 10, max = 72) String password,
		@NotBlank @Size(max = 200) String fullName) {

	@Override
	public String toString() {
		return "RegisterRequest[redacted]";
	}

}
