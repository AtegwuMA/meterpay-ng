package com.theodomart.energy.security.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.common.domain.Role;
import com.theodomart.energy.security.config.JwtKeys;
import com.theodomart.energy.security.config.JwtProperties;
import com.theodomart.energy.security.service.IssuedToken;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtTokenServiceTest {

	private static final Instant ISSUED = Instant.parse("2026-10-26T10:00:00Z");

	private static final JwtProperties PROPERTIES = new JwtProperties(
			"unit-test-secret-0123456789-abcdefghijklmnopqrstuv", Duration.ofMinutes(15), "meterpay");

	private final UUID userId = UUID.randomUUID();

	private final IssuedToken token = new JwtTokenService(JwtKeys.encoder(PROPERTIES), PROPERTIES, clockAt(ISSUED))
		.issueAccessToken(userId, Role.LANDLORD);

	@Test
	void issuesTokenWithSubjectRoleIssuerAndFifteenMinuteExpiry() {
		Jwt jwt = JwtKeys.decoder(PROPERTIES, clockAt(ISSUED)).decode(token.value());

		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.getClaimAsString("role")).isEqualTo("LANDLORD");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("meterpay");
		assertThat(jwt.getIssuedAt()).isEqualTo(ISSUED);
		assertThat(jwt.getExpiresAt()).isEqualTo(ISSUED.plus(Duration.ofMinutes(15))).isEqualTo(token.expiresAt());
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
	}

	@Test
	void carriesNoPersonalData() {
		Jwt jwt = JwtKeys.decoder(PROPERTIES, clockAt(ISSUED)).decode(token.value());

		assertThat(jwt.getClaims()).containsOnlyKeys("iss", "sub", "iat", "exp", "role");
	}

	@Test
	void expiredTokenIsRejectedOnceClockSkewIsUsedUp() {
		Instant justInsideSkew = token.expiresAt().plusSeconds(29);
		Instant pastSkew = token.expiresAt().plusSeconds(31);

		assertThat(JwtKeys.decoder(PROPERTIES, clockAt(justInsideSkew)).decode(token.value())).isNotNull();
		assertThatThrownBy(() -> JwtKeys.decoder(PROPERTIES, clockAt(pastSkew)).decode(token.value()))
			.isInstanceOf(JwtException.class)
			.hasMessageContaining("expired");
	}

	@Test
	void tamperedPayloadIsRejected() {
		String[] parts = token.value().split("\\.");
		String forgedPayload = base64Url("{\"iss\":\"meterpay\",\"sub\":\"" + userId
				+ "\",\"role\":\"ADMIN\",\"iat\":1793008800,\"exp\":4102444800}");
		String forged = parts[0] + "." + forgedPayload + "." + parts[2];

		assertThatThrownBy(() -> JwtKeys.decoder(PROPERTIES, clockAt(ISSUED)).decode(forged))
			.isInstanceOf(JwtException.class);
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() {
		JwtProperties attacker = new JwtProperties("attacker-secret-0123456789-abcdefghijklmnopqrs",
				Duration.ofMinutes(15), "meterpay");
		String foreign = new JwtTokenService(JwtKeys.encoder(attacker), attacker, clockAt(ISSUED))
			.issueAccessToken(userId, Role.ADMIN)
			.value();

		assertThatThrownBy(() -> JwtKeys.decoder(PROPERTIES, clockAt(ISSUED)).decode(foreign))
			.isInstanceOf(JwtException.class);
	}

	@Test
	void tokenFromAnotherIssuerIsRejected() {
		JwtProperties otherIssuer = new JwtProperties(PROPERTIES.secret(), Duration.ofMinutes(15), "someone-else");
		String foreign = new JwtTokenService(JwtKeys.encoder(otherIssuer), otherIssuer, clockAt(ISSUED))
			.issueAccessToken(userId, Role.LANDLORD)
			.value();

		assertThatThrownBy(() -> JwtKeys.decoder(PROPERTIES, clockAt(ISSUED)).decode(foreign))
			.isInstanceOf(JwtException.class);
	}

	@Test
	void unsignedAlgNoneTokenIsRejected() {
		String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
		String payload = base64Url("{\"iss\":\"meterpay\",\"sub\":\"" + userId
				+ "\",\"role\":\"ADMIN\",\"iat\":1793008800,\"exp\":4102444800}");

		assertThatThrownBy(() -> JwtKeys.decoder(PROPERTIES, clockAt(ISSUED)).decode(header + "." + payload + "."))
			.isInstanceOf(JwtException.class);
	}

	@Test
	void issuedTokenDoesNotPrintItsValue() {
		assertThat(token.toString()).doesNotContain(token.value());
	}

	private static Clock clockAt(Instant instant) {
		return Clock.fixed(instant, ZoneOffset.UTC);
	}

	private static String base64Url(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

}
