package com.theodomart.energy.identity.repository;

import com.theodomart.energy.identity.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	/**
	 * SELECT ... FOR UPDATE. Two requests presenting the same token are serialised: the first rotates it, the
	 * second then finds it retired and is treated as reuse. Without the lock both could rotate it.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
	Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

	/** Revokes every live token in the family. Bulk update, so the version is bumped by hand. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update RefreshToken t
			set t.revokedAt = :now, t.updatedAt = :now, t.version = t.version + 1
			where t.familyId = :familyId and t.revokedAt is null
			""")
	int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

}
