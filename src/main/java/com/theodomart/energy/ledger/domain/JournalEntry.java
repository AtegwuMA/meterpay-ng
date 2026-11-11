package com.theodomart.energy.ledger.domain;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.ledger.service.Direction;
import com.theodomart.energy.ledger.service.EntryType;
import com.theodomart.energy.ledger.service.PostingLine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One movement of money. Never changed after it is written (V12 refuses it in the database too), so @Immutable
 * stops Hibernate from ever issuing an UPDATE for it, even if a field were changed by mistake.
 */
@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry {

	static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

	static final int MAX_REFERENCE_TYPE_LENGTH = 32;

	static final int MAX_DESCRIPTION_LENGTH = 255;

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_type", nullable = false)
	private EntryType entryType;

	@Column(name = "reference_type")
	private String referenceType;

	@Column(name = "reference_id")
	private UUID referenceId;

	@Column(name = "idempotency_key", nullable = false)
	private String idempotencyKey;

	private String description;

	@Column(name = "posted_at", nullable = false)
	private Instant postedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected JournalEntry() {
		// for JPA
	}

	private JournalEntry(EntryType entryType, String idempotencyKey, String referenceType, UUID referenceId,
			String description, Instant now) {
		this.id = UUID.randomUUID();
		this.entryType = entryType;
		this.idempotencyKey = idempotencyKey;
		this.referenceType = referenceType;
		this.referenceId = referenceId;
		this.description = description;
		this.postedAt = now.truncatedTo(ChronoUnit.MICROS);
		this.createdAt = this.postedAt;
	}

	/**
	 * Checks everything about an entry that does not need the database, including that its lines balance.
	 *
	 * @throws IllegalArgumentException if anything is wrong: these come from code, never from a user, so a bad one is
	 * a bug to fix rather than a request to refuse politely
	 */
	public static JournalEntry create(EntryType type, String idempotencyKey, String referenceType, UUID referenceId,
			String description, List<PostingLine> lines, Instant now) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(now, "now");
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("An entry needs an idempotency key");
		}
		if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new IllegalArgumentException("Idempotency key is longer than " + MAX_IDEMPOTENCY_KEY_LENGTH);
		}
		if ((referenceType == null) != (referenceId == null)) {
			throw new IllegalArgumentException("A reference needs both its type and its id");
		}
		if (referenceType != null && referenceType.length() > MAX_REFERENCE_TYPE_LENGTH) {
			throw new IllegalArgumentException("Reference type is longer than " + MAX_REFERENCE_TYPE_LENGTH);
		}
		if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
			throw new IllegalArgumentException("Description is longer than " + MAX_DESCRIPTION_LENGTH);
		}
		requireBalanced(lines);
		return new JournalEntry(type, idempotencyKey, referenceType, referenceId, description, now);
	}

	/**
	 * The ledger's central rule (DESIGN 6.3): at least two lines, and debits equal credits to the kobo. Amounts are
	 * positive by construction (PostingLine), so balanced lines always include at least one of each side.
	 *
	 * @throws IllegalArgumentException if they do not balance
	 * @throws ArithmeticException if a total overflows
	 */
	public static void requireBalanced(List<PostingLine> lines) {
		if (lines == null || lines.size() < 2) {
			throw new IllegalArgumentException("An entry needs at least two postings");
		}
		long debits = 0;
		long credits = 0;
		for (PostingLine line : lines) {
			if (line.direction() == Direction.DEBIT) {
				debits = Math.addExact(debits, line.amount().kobo());
			}
			else {
				credits = Math.addExact(credits, line.amount().kobo());
			}
		}
		if (debits != credits) {
			throw new IllegalArgumentException("Debits of " + Money.ofKobo(debits).format() + " and credits of "
					+ Money.ofKobo(credits).format() + " do not balance");
		}
	}

	public UUID getId() {
		return id;
	}

	public EntryType getEntryType() {
		return entryType;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getReferenceType() {
		return referenceType;
	}

	public UUID getReferenceId() {
		return referenceId;
	}

	public Instant getPostedAt() {
		return postedAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof JournalEntry other && Objects.equals(id, other.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	@Override
	public String toString() {
		return "JournalEntry[id=" + id + ", type=" + entryType + ", key=" + idempotencyKey + "]";
	}

}
