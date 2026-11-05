package com.theodomart.energy.identity.service;

import java.util.UUID;

public interface IdentityService {

	/**
	 * Registers a landlord. Landlords sign up on their own; tenants need an invite code.
	 *
	 * @throws DuplicateAccountException if the email or phone already belongs to an account
	 */
	UserView registerLandlord(RegisterCommand command);

	/**
	 * Registers a tenant and starts their tenancy from an invite code. One transaction: if the code is bad or
	 * the account is a duplicate, no user exists afterwards and the code stays usable.
	 *
	 * @throws DuplicateAccountException if the email or phone already belongs to an account
	 * @throws com.theodomart.energy.property.service.InvalidInviteCodeException if the code cannot be used
	 */
	UserView registerTenant(RegisterCommand command, String inviteCode);

	/**
	 * Starts a new refresh token family.
	 *
	 * @throws InvalidCredentialsException for an unknown email, a wrong password or a disabled account, with
	 * the same message for all three so callers cannot tell which
	 */
	AuthTokens login(String email, String password);

	/**
	 * Rotates the refresh token: the presented one is retired and a new one in the same family is returned.
	 * Presenting a token that was already retired revokes its whole family (DESIGN 11).
	 *
	 * @throws InvalidRefreshTokenException for an unknown, expired, revoked or reused token
	 */
	AuthTokens refresh(String refreshToken);

	/** Revokes the family of the given refresh token, if it belongs to the user. Silent otherwise. */
	void logout(UUID userId, String refreshToken);

	UserView currentUser(UUID userId);

}
