package com.theodomart.energy.security.config;

import com.theodomart.energy.common.domain.Role;
import com.theodomart.energy.security.service.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** Turns a verified JWT into a CurrentUser principal with a ROLE_ authority. */
final class JwtCurrentUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		CurrentUser user;
		try {
			user = new CurrentUser(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			// Correctly signed but unreadable: an invalid token (401), never a server error.
			throw new InvalidBearerTokenException("Token subject or role is invalid");
		}
		return UsernamePasswordAuthenticationToken.authenticated(user, null,
				List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
	}

}
