package com.theodomart.energy.property.repository;

import com.theodomart.energy.property.domain.TenantInvite;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantInviteRepository extends JpaRepository<TenantInvite, UUID> {

	/** SELECT ... FOR UPDATE: two people racing with one code are serialised, and the second finds it used. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from TenantInvite i where i.codeHash = :codeHash")
	Optional<TenantInvite> findByCodeHashForUpdate(@Param("codeHash") String codeHash);

	/** Bulk update, so the version is bumped by hand. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update TenantInvite i
			set i.revokedAt = :now, i.updatedAt = :now, i.version = i.version + 1
			where i.unitId = :unitId and i.acceptedAt is null and i.revokedAt is null
			""")
	int revokeOpenInvites(@Param("unitId") UUID unitId, @Param("now") Instant now);

}
