package com.theodomart.energy.identity.serviceImpl;

import com.theodomart.energy.common.domain.Role;
import com.theodomart.energy.common.error.InvalidRequestException;
import com.theodomart.energy.identity.config.IdentityProperties;
import com.theodomart.energy.identity.domain.OpaqueTokens;
import com.theodomart.energy.identity.domain.PhoneNumbers;
import com.theodomart.energy.identity.domain.RefreshToken;
import com.theodomart.energy.identity.domain.User;
import com.theodomart.energy.identity.domain.UserStatus;
import com.theodomart.energy.identity.repository.RefreshTokenRepository;
import com.theodomart.energy.identity.repository.UserRepository;
import com.theodomart.energy.identity.service.AuthTokens;
import com.theodomart.energy.identity.service.DuplicateAccountException;
import com.theodomart.energy.identity.service.IdentityService;
import com.theodomart.energy.identity.service.InvalidCredentialsException;
import com.theodomart.energy.identity.service.InvalidRefreshTokenException;
import com.theodomart.energy.identity.service.RegisterCommand;
import com.theodomart.energy.identity.service.UserView;
import com.theodomart.energy.security.service.IssuedToken;
import com.theodomart.energy.security.service.TokenService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityServiceImpl implements IdentityService {

	/** BCrypt only reads the first 72 bytes; longer input would silently be truncated, so it is refused. */
	static final int BCRYPT_MAX_BYTES = 72;

	private static final Logger log = LoggerFactory.getLogger(IdentityServiceImpl.class);

	private final UserRepository users;

	private final RefreshTokenRepository refreshTokens;

	private final PasswordEncoder passwordEncoder;

	private final TokenService tokens;

	private final IdentityProperties properties;

	private final Clock clock;

	// Compared against when the email is unknown, so that path costs one BCrypt check like a real login.
	// Without it, response time alone would tell an attacker which emails are registered.
	private final String dummyHash;

	public IdentityServiceImpl(UserRepository users, RefreshTokenRepository refreshTokens,
			PasswordEncoder passwordEncoder, TokenService tokens, IdentityProperties properties, Clock clock) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.passwordEncoder = passwordEncoder;
		this.tokens = tokens;
		this.properties = properties;
		this.clock = clock;
		this.dummyHash = passwordEncoder.encode("timing-equaliser-" + UUID.randomUUID());
	}

	@Override
	@Transactional
	public UserView registerLandlord(RegisterCommand command) {
		if (command.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new InvalidRequestException("Password must be at most " + BCRYPT_MAX_BYTES + " bytes.");
		}
		String phone;
		try {
			phone = PhoneNumbers.toE164(command.phone());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage() + ".");
		}
		User user = User.create(command.email(), phone, passwordEncoder.encode(command.password()),
				command.fullName(), Role.LANDLORD, clock.instant());
		try {
			// Insert and let the UNIQUE constraints decide. A check-then-insert would race: two concurrent
			// registrations could both pass the check.
			users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			throw duplicateOrRethrow(ex);
		}
		log.info("Landlord registered: userId={}", user.getId());
		return toView(user);
	}

	@Override
	@Transactional
	public AuthTokens login(String email, String password) {
		Optional<User> found = users.findByEmail(User.normaliseEmail(email));
		boolean tooLong = password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
		// Always run exactly one BCrypt check, whatever went wrong, so every failure takes the same time.
		boolean passwordMatches = !tooLong
				&& passwordEncoder.matches(password, found.map(User::getPasswordHash).orElse(dummyHash));
		if (found.isEmpty() || !passwordMatches || found.get().getStatus() != UserStatus.ACTIVE) {
			log.info("Login failed");
			throw new InvalidCredentialsException();
		}
		User user = found.get();
		Instant now = clock.instant();
		String rawRefresh = OpaqueTokens.generate();
		RefreshToken refresh = refreshTokens.save(RefreshToken.startFamily(user.getId(),
				OpaqueTokens.sha256Hex(rawRefresh), now, properties.refreshTokenTtl()));
		log.info("Login succeeded: userId={} familyId={}", user.getId(), refresh.getFamilyId());
		return new AuthTokens(tokens.issueAccessToken(user.getId(), user.getRole()), rawRefresh,
				refresh.getCreatedAt(), refresh.getExpiresAt());
	}

	/*
	 * noRollbackFor: when reuse is detected the family revocation must be committed even though the call ends
	 * in an exception. With the default rollback, the attacker gets a 401 but the stolen family stays alive.
	 */
	@Override
	@Transactional(noRollbackFor = InvalidRefreshTokenException.class)
	public AuthTokens refresh(String refreshToken) {
		Instant now = clock.instant();
		RefreshToken current = refreshTokens.findByTokenHashForUpdate(OpaqueTokens.sha256Hex(refreshToken))
			.orElseThrow(InvalidRefreshTokenException::new);

		if (current.isRetired()) {
			// Either the legitimate client or a thief already used this token. We cannot tell which, so every
			// token in the family dies and the real user has to log in again.
			int revoked = refreshTokens.revokeFamily(current.getFamilyId(), now);
			log.warn("Refresh token reuse detected, family revoked: userId={} familyId={} revokedTokens={}",
					current.getUserId(), current.getFamilyId(), revoked);
			throw new InvalidRefreshTokenException();
		}
		if (!current.isUsable(now)) {
			throw new InvalidRefreshTokenException();
		}
		User user = users.findById(current.getUserId())
			.filter(u -> u.getStatus() == UserStatus.ACTIVE)
			.orElse(null);
		if (user == null) {
			refreshTokens.revokeFamily(current.getFamilyId(), now);
			throw new InvalidRefreshTokenException();
		}

		String rawNext = OpaqueTokens.generate();
		RefreshToken next = current.rotate(OpaqueTokens.sha256Hex(rawNext), now, properties.refreshTokenTtl());
		refreshTokens.save(next);
		IssuedToken access = tokens.issueAccessToken(user.getId(), user.getRole());
		return new AuthTokens(access, rawNext, next.getCreatedAt(), next.getExpiresAt());
	}

	@Override
	@Transactional
	public void logout(UUID userId, String refreshToken) {
		// Only the owner can end a session. Someone else's token, or an unknown one, is ignored without saying
		// so, so logout cannot be used to probe which tokens exist.
		refreshTokens.findByTokenHashForUpdate(OpaqueTokens.sha256Hex(refreshToken))
			.filter(token -> token.getUserId().equals(userId))
			.ifPresent(token -> {
				refreshTokens.revokeFamily(token.getFamilyId(), clock.instant());
				log.info("Logged out: userId={} familyId={}", userId, token.getFamilyId());
			});
	}

	@Override
	@Transactional(readOnly = true)
	public UserView currentUser(UUID userId) {
		// A valid token for a user that no longer exists or was disabled is treated as not authenticated.
		return users.findById(userId)
			.filter(user -> user.getStatus() == UserStatus.ACTIVE)
			.map(IdentityServiceImpl::toView)
			.orElseThrow(InvalidCredentialsException::new);
	}

	private static RuntimeException duplicateOrRethrow(DataIntegrityViolationException ex) {
		String constraint = constraintName(ex);
		if ("uk_users_email".equals(constraint)) {
			return new DuplicateAccountException("email");
		}
		if ("uk_users_phone".equals(constraint)) {
			return new DuplicateAccountException("phone number");
		}
		return ex;
	}

	private static String constraintName(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getConstraintName();
			}
		}
		return null;
	}

	private static UserView toView(User user) {
		return new UserView(user.getId(), user.getEmail(), user.getPhone(), user.getFullName(), user.getRole(),
				user.getCreatedAt());
	}

}
