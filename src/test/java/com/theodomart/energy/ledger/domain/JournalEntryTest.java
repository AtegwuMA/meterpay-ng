package com.theodomart.energy.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.AccountType;
import com.theodomart.energy.ledger.service.Direction;
import com.theodomart.energy.ledger.service.EntryType;
import com.theodomart.energy.ledger.service.PostingLine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class JournalEntryTest {

	private static final Instant NOW = Instant.parse("2026-11-11T09:00:00.123456789Z");

	private static final List<PostingLine> TOP_UP = List.of(
			PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(500_000)),
			PostingLine.credit(AccountRef.tenantWallet(UUID.randomUUID()), Money.ofKobo(500_000)));

	@Test
	void anEntryKeepsWhatItWasGiven() {
		UUID intent = UUID.randomUUID();

		JournalEntry entry = JournalEntry.create(EntryType.WALLET_TOPUP, "topup:" + intent, "PAYMENT_INTENT", intent,
				"Wallet top-up", TOP_UP, NOW);

		assertThat(entry.getId()).isNotNull();
		assertThat(entry.getEntryType()).isEqualTo(EntryType.WALLET_TOPUP);
		assertThat(entry.getIdempotencyKey()).isEqualTo("topup:" + intent);
		assertThat(entry.getReferenceType()).isEqualTo("PAYMENT_INTENT");
		assertThat(entry.getReferenceId()).isEqualTo(intent);
		assertThat(entry.getPostedAt()).isEqualTo("2026-11-11T09:00:00.123456Z");
		assertThat(entry).isEqualTo(entry).hasSameHashCodeAs(entry).isNotEqualTo("entry");
		assertThat(entry.toString()).contains("WALLET_TOPUP");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = "   ")
	void anEntryNeedsAnIdempotencyKey(String key) {
		assertThatThrownBy(() -> JournalEntry.create(EntryType.WALLET_TOPUP, key, null, null, null, TOP_UP, NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("idempotency key");
	}

	@Test
	void textLongerThanItsColumnIsRefusedBeforeTheDatabaseSeesIt() {
		String key = "k".repeat(JournalEntry.MAX_IDEMPOTENCY_KEY_LENGTH + 1);
		String type = "T".repeat(JournalEntry.MAX_REFERENCE_TYPE_LENGTH + 1);
		String description = "d".repeat(JournalEntry.MAX_DESCRIPTION_LENGTH + 1);

		assertThatThrownBy(() -> JournalEntry.create(EntryType.ADJUSTMENT, key, null, null, null, TOP_UP, NOW))
			.hasMessageContaining("Idempotency key");
		assertThatThrownBy(
				() -> JournalEntry.create(EntryType.ADJUSTMENT, "k", type, UUID.randomUUID(), null, TOP_UP, NOW))
			.hasMessageContaining("Reference type");
		assertThatThrownBy(() -> JournalEntry.create(EntryType.ADJUSTMENT, "k", null, null, description, TOP_UP, NOW))
			.hasMessageContaining("Description");
	}

	@Test
	void aReferenceNeedsBothParts() {
		assertThatThrownBy(() -> JournalEntry.create(EntryType.ADJUSTMENT, "k", "PAYMENT_INTENT", null, null, TOP_UP,
				NOW))
			.hasMessageContaining("both");
		assertThatThrownBy(
				() -> JournalEntry.create(EntryType.ADJUSTMENT, "k", null, UUID.randomUUID(), null, TOP_UP, NOW))
			.hasMessageContaining("both");
	}

	@Test
	void aPostingKeepsWhatItWasGiven() {
		UUID entry = UUID.randomUUID();
		UUID account = UUID.randomUUID();

		Posting posting = Posting.of(entry, account, Direction.DEBIT, 750, NOW);

		assertThat(posting.getId()).isNotNull();
		assertThat(posting.getJournalEntryId()).isEqualTo(entry);
		assertThat(posting.getAccountId()).isEqualTo(account);
		assertThat(posting.getDirection()).isEqualTo(Direction.DEBIT);
		assertThat(posting.getAmountKobo()).isEqualTo(750);
		assertThat(posting).isEqualTo(posting).hasSameHashCodeAs(posting).isNotEqualTo("posting");
		assertThat(posting.toString()).contains("DEBIT 750");
	}

	@Test
	void accountReferencesFollowTheOwnerRules() {
		assertThat(AccountRef.landlordRevenue(UUID.randomUUID()).type()).isEqualTo(AccountType.LANDLORD_REVENUE);
		assertThatThrownBy(() -> new AccountRef(AccountType.TENANT_WALLET, null)).hasMessageContaining("needs an owner");
		assertThatThrownBy(() -> new AccountRef(AccountType.VAT_PAYABLE, UUID.randomUUID()))
			.hasMessageContaining("has no owner");
	}

}
