package com.theodomart.energy.identity.service;

import com.theodomart.energy.security.service.IssuedToken;
import java.util.UUID;

public interface IdentityService {

	/**
	 * Registers a landlord. Only landlords self-register for now; tenants join through an invite code (M1-06).
	 *
	 * @throws DuplicateAccountException if the email or phone already belongs to an account
	 */
	UserView registerLandlord(RegisterCommand command);

	/**
	 * @throws InvalidCredentialsException for an unknown email, a wrong password or a disabled account, with
	 * the same message for all three so callers cannot tell which
	 */
	IssuedToken login(String email, String password);

	UserView currentUser(UUID userId);

}
