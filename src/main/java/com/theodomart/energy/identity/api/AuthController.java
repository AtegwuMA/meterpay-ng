package com.theodomart.energy.identity.api;

import com.theodomart.energy.identity.service.IdentityService;
import com.theodomart.energy.identity.service.RegisterCommand;
import com.theodomart.energy.security.service.CurrentUser;
import com.theodomart.energy.security.service.IssuedToken;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
public class AuthController {

	private final IdentityService identity;

	public AuthController(IdentityService identity) {
		this.identity = identity;
	}

	@PostMapping("/auth/register")
	public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
		var user = identity.registerLandlord(
				new RegisterCommand(request.email(), request.phone(), request.password(), request.fullName()));
		return ResponseEntity.created(URI.create("/api/v1/me")).body(UserResponse.from(user));
	}

	@PostMapping("/auth/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		IssuedToken token = identity.login(request.email(), request.password());
		// From the token's own timestamps, not a second clock read: that would drift and report 899 for a 900s token.
		long expiresIn = Duration.between(token.issuedAt(), token.expiresAt()).toSeconds();
		return new TokenResponse(token.value(), "Bearer", expiresIn);
	}

	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal CurrentUser currentUser) {
		return UserResponse.from(identity.currentUser(currentUser.id()));
	}

}
