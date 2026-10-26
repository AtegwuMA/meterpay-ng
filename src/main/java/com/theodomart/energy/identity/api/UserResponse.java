package com.theodomart.energy.identity.api;

import com.theodomart.energy.common.domain.Role;
import com.theodomart.energy.identity.service.UserView;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, String phone, String fullName, Role role, Instant createdAt) {

	static UserResponse from(UserView view) {
		return new UserResponse(view.id(), view.email(), view.phone(), view.fullName(), view.role(),
				view.createdAt());
	}

}
