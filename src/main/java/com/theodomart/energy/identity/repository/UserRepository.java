package com.theodomart.energy.identity.repository;

import com.theodomart.energy.identity.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

	/** Expects the normalised (lower case) form; see {@link User#normaliseEmail}. */
	Optional<User> findByEmail(String email);

}
