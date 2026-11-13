package com.theodomart.energy.ledger.domain;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.ledger.service.AccountType;
import com.theodomart.energy.ledger.service.Direction;
import com.theodomart.energy.ledger.service.InsufficientWalletBalanceException;
import com.theodomart.energy.ledger.service.OwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One account in the ledger. The balance is a running total of the account's postings, kept so it can be read and
 * locked cheaply. Posting (M3-03) is the only thing that changes it.
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

	public static final String CURRENCY = "NGN";

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "owner_type", nullable = false, updatable = false)
	private OwnerType ownerType;

	@Column(name = "owner_id", updatable = false)
	private UUID ownerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private AccountType type;

	@Column(nullable = false, updatable = false)
	private String currency;

	@Column(name = "balance_kobo", nullable = false)
	private long balanceKobo;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected LedgerAccount() {
		// for JPA
	}

	private LedgerAccount(AccountType type, UUID ownerId, Instant now) {
		this.id = UUID.randomUUID();
		this.type = type;
		this.ownerType = type.ownerType();
		this.ownerId = ownerId;
		this.currency = CURRENCY;
		this.balanceKobo = 0;
		this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
		this.updatedAt = this.createdAt;
		this.version = 0L;
	}

	/**
	 * A new, empty account. Times are cut to microseconds, the precision Postgres stores.
	 *
	 * @throws IllegalArgumentException if a person's account has no owner, or a platform account has one
	 */
	public static LedgerAccount open(AccountType type, UUID ownerId, Instant now) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(now, "now");
		boolean platform = type.ownerType() == OwnerType.PLATFORM;
		if (platform != (ownerId == null)) {
			throw new IllegalArgumentException(
					platform ? "A platform account has no owner" : "A " + type + " account needs an owner");
		}
		return new LedgerAccount(type, ownerId, now);
	}

	/**
	 * Applies one entry's lines for this account, all at once so a wallet that is credited and debited in the same
	 * entry is judged on the result. Balances are positive in the account's normal direction (DESIGN 6.3).
	 *
	 * @throws InsufficientWalletBalanceException if a wallet would go below zero: the only account with a floor
	 * @throws ArithmeticException if the balance would overflow
	 */
	public void post(long debitsKobo, long creditsKobo, Instant now) {
		long change = type.normalBalance() == Direction.CREDIT ? Math.subtractExact(creditsKobo, debitsKobo)
				: Math.subtractExact(debitsKobo, creditsKobo);
		long after = Math.addExact(balanceKobo, change);
		if (type == AccountType.TENANT_WALLET && after < 0) {
			throw new InsufficientWalletBalanceException(Money.ofKobo(balanceKobo), Money.ofKobo(-change));
		}
		this.balanceKobo = after;
		this.updatedAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
	}

	public UUID getId() {
		return id;
	}

	public OwnerType getOwnerType() {
		return ownerType;
	}

	public UUID getOwnerId() {
		return ownerId;
	}

	public AccountType getType() {
		return type;
	}

	public String getCurrency() {
		return currency;
	}

	public long getBalanceKobo() {
		return balanceKobo;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof LedgerAccount other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "LedgerAccount[id=" + id + ", type=" + type + ", ownerId=" + ownerId + "]";
	}

}
