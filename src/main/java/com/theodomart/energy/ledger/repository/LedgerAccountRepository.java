package com.theodomart.energy.ledger.repository;

import com.theodomart.energy.ledger.domain.LedgerAccount;
import com.theodomart.energy.ledger.service.AccountType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** A plain Repository, not JpaRepository, so accounts have no delete methods to call (DESIGN 5.2 rule 4). */
public interface LedgerAccountRepository extends Repository<LedgerAccount, UUID> {

	/** Only the id, so nothing is loaded before the row is locked. */
	@Query("SELECT a.id FROM LedgerAccount a WHERE a.type = :type AND a.ownerId = :ownerId")
	Optional<UUID> findIdByTypeAndOwnerId(@Param("type") AccountType type, @Param("ownerId") UUID ownerId);

	/** Platform accounts: a separate method because "owner_id = NULL" is never true in SQL. */
	@Query("SELECT a.id FROM LedgerAccount a WHERE a.type = :type AND a.ownerId IS NULL")
	Optional<UUID> findIdByTypeAndOwnerIdIsNull(@Param("type") AccountType type);

	/**
	 * SELECT ... FOR UPDATE ORDER BY id: Postgres locks the rows in the sorted order, so every posting takes its locks
	 * in one global order and two postings can never each hold a row the other needs. The balances read here are
	 * current, because nobody else can change these rows until this transaction ends.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM LedgerAccount a WHERE a.id IN :ids ORDER BY a.id")
	List<LedgerAccount> lockAllInIdOrder(@Param("ids") Collection<UUID> ids);

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
