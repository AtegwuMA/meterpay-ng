package com.theodomart.energy.security.serviceImpl;

import com.theodomart.energy.common.domain.Role;
import com.theodomart.energy.security.config.JwtProperties;
import com.theodomart.energy.security.service.IssuedToken;
import com.theodomart.energy.security.service.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService implements TokenService {

	private final JwtEncoder encoder;

	private final JwtProperties properties;

	private final Clock clock;

	public JwtTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public IssuedToken issueAccessToken(UUID userId, Role role) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(properties.accessTokenTtl());
		// Only what authorisation needs. A JWT is signed, not encrypted, so anyone holding it can read every
		// claim: no email, phone or name in here.
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(properties.issuer())
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(expiresAt)
			.claim("role", role.name())
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, now, expiresAt);
	}

}
