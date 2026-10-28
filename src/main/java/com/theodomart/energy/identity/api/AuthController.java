package com.theodomart.energy.identity.api;

import com.theodomart.energy.identity.service.IdentityService;
import com.theodomart.energy.identity.service.RegisterCommand;
import com.theodomart.energy.security.service.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
		return TokenResponse.from(identity.login(request.email(), request.password()));
	}

	@PostMapping("/auth/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return TokenResponse.from(identity.refresh(request.refreshToken()));
	}

	/**
	 * Ends this device's session by revoking its refresh token family. 204 whatever happened, so the response
	 * never reveals whether a token existed. The access token stays valid until it expires (at most 15 min).
	 */
	@PostMapping("/auth/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@AuthenticationPrincipal CurrentUser currentUser, @Valid @RequestBody RefreshRequest request) {
		identity.logout(currentUser.id(), request.refreshToken());
	}

	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal CurrentUser currentUser) {
		return UserResponse.from(identity.currentUser(currentUser.id()));
	}

}
