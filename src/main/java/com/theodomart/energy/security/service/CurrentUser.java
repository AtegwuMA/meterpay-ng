package com.theodomart.energy.security.service;

import com.theodomart.energy.common.domain.Role;
import java.util.UUID;

/**
 * The authenticated caller, taken from a verified access token. Controllers receive it with
 * {@code @AuthenticationPrincipal}.
 */
public record CurrentUser(UUID id, Role role) {

}
