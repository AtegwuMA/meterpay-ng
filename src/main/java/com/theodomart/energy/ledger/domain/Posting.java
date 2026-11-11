package com.theodomart.energy.ledger.domain;

import com.theodomart.energy.ledger.service.Direction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One line of a journal entry, as stored. Never changed after it is written, like its entry. */
@Entity
@Immutable
@Table(name = "postings")
public class Posting {

	@Id
	private UUID id;

	@Column(name = "journal_entry_id", nullable = false)
	private UUID journalEntryId;

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Direction direction;

	@Column(name = "amount_kobo", nullable = false)
	private long amountKobo;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Posting() {
		// for JPA
	}

	private Posting(UUID journalEntryId, UUID accountId, Direction direction, long amountKobo, Instant now) {
		this.id = UUID.randomUUID();
		this.journalEntryId = Objects.requireNonNull(journalEntryId, "journalEntryId");
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.direction = Objects.requireNonNull(direction, "direction");
		this.amountKobo = amountKobo;
		this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
	}

	/** @throws IllegalArgumentException if the amount is not positive */
	public static Posting of(UUID journalEntryId, UUID accountId, Direction direction, long amountKobo, Instant now) {
		if (amountKobo <= 0) {
			throw new IllegalArgumentException("A posting must move more than zero");
		}
		return new Posting(journalEntryId, accountId, direction, amountKobo, Objects.requireNonNull(now, "now"));
	}

	public UUID getId() {
		return id;
	}

	public UUID getJournalEntryId() {
		return journalEntryId;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public Direction getDirection() {
		return direction;
	}

	public long getAmountKobo() {
		return amountKobo;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof Posting other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "Posting[id=" + id + ", accountId=" + accountId + ", " + direction + " " + amountKobo + "]";
	}

}
