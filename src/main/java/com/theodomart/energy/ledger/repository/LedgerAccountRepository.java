package com.theodomart.energy.ledger.repository;

import com.theodomart.energy.ledger.domain.LedgerAccount;
import com.theodomart.energy.ledger.service.AccountType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** A plain Repository, not JpaRepository, so accounts have no delete methods to call (DESIGN 5.2 rule 4). */
public interface LedgerAccountRepository extends Repository<LedgerAccount, UUID> {

	Optional<LedgerAccount> findByTypeAndOwnerId(AccountType type, UUID ownerId);

	/** Platform accounts: a separate method because "owner_id = NULL" is never true in SQL. */
	Optional<LedgerAccount> findByTypeAndOwnerIdIsNull(AccountType type);

	/** Flushes straight away, so the caller decides the order of balance updates, not Hibernate. */
	LedgerAccount saveAndFlush(LedgerAccount account);

	/**
	 * Opens the account unless the owner already has one of this type, and returns how many rows it inserted. ON
	 * CONFLICT rather than catching the violation: in Postgres a failed insert aborts the caller's transaction, which
	 * here is the registration itself.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO ledger_accounts (id, owner_type, owner_id, type, currency, balance_kobo, created_at, updated_at, version)
			VALUES (:id, :ownerType, :ownerId, :type, :currency, 0, :now, :now, 0)
			ON CONFLICT (owner_type, owner_id, type) DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") UUID id, @Param("ownerType") String ownerType, @Param("ownerId") UUID ownerId,
			@Param("type") String type, @Param("currency") String currency, @Param("now") Instant now);

}
