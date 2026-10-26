package com.theodomart.energy.security.service;

import com.theodomart.energy.common.domain.Role;
import java.util.UUID;

public interface TokenService {

	/** A signed, short-lived JWT access token (DESIGN 11: HS256, 15 minutes, claims sub and role). */
	IssuedToken issueAccessToken(UUID userId, Role role);

}
