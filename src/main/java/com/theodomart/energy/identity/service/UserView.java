package com.theodomart.energy.identity.service;

import com.theodomart.energy.common.domain.Role;
import java.time.Instant;
import java.util.UUID;

/** A read-only view of a user for other layers and modules. Never carries the password hash. */
public record UserView(UUID id, String email, String phone, String fullName, Role role, Instant createdAt) {

}
