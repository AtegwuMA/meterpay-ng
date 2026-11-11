package com.theodomart.energy.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.Direction;
import com.theodomart.energy.ledger.service.EntryType;
import com.theodomart.energy.ledger.service.PostingLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DESIGN 6.3 invariants that hold for every entry, checked on many generated entries rather than a few examples. */
class LedgerInvariantTest {

	private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");

	private static final List<AccountRef> ACCOUNTS = List.of(AccountRef.tenantWallet(UUID.randomUUID()),
			AccountRef.landlordRevenue(UUID.randomUUID()), AccountRef.gatewayClearing(), AccountRef.vatPayable(),
			AccountRef.platformFeeRevenue());

	@Test
	void everyJournalEntryBalances() {
		// Fixed seed: a failure can be reproduced exactly. Each round tries a balanced set of lines and the same set
		// with one line off by a single kobo, which must be the only difference between accepted and refused.
		Random random = new Random(20261111L);
		for (int round = 0; round < 5_000; round++) {
			List<PostingLine> balanced = balancedLines(random);
			List<PostingLine> offByOne = withOneKoboMore(balanced, random.nextInt(balanced.size()));

			assertThat(JournalEntry.create(EntryType.ADJUSTMENT, "key-" + round, null, null, null, balanced, NOW))
				.as("round %d", round)
				.isNotNull();
			assertThat(total(balanced, Direction.DEBIT)).isEqualTo(total(balanced, Direction.CREDIT));
			assertThatThrownBy(() -> JournalEntry.requireBalanced(offByOne)).as("round %d", round)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("do not balance");
		}
	}

	@Test
	void theDesignRecipesBalance() {
		UUID tenant = UUID.randomUUID();
		AccountRef wallet = AccountRef.tenantWallet(tenant);

		JournalEntry.requireBalanced(List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(500_000)),
				PostingLine.credit(wallet, Money.ofKobo(500_000))));
		JournalEntry.requireBalanced(List.of(PostingLine.debit(wallet, Money.ofKobo(200_000)),
				PostingLine.credit(AccountRef.vatPayable(), Money.ofKobo(13_953)),
				PostingLine.credit(AccountRef.platformFeeRevenue(), Money.ofKobo(3_720)),
				PostingLine.credit(AccountRef.landlordRevenue(UUID.randomUUID()), Money.ofKobo(182_327))));
	}

	@Test
	void anEntryNeedsAtLeastTwoLines() {
		List<PostingLine> one = List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(1)));

		assertThatThrownBy(() -> JournalEntry.requireBalanced(one)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("two");
		assertThatThrownBy(() -> JournalEntry.requireBalanced(List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JournalEntry.requireBalanced(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void twoDebitsAndNoCreditDoNotBalance() {
		List<PostingLine> lines = List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(5)),
				PostingLine.debit(AccountRef.vatPayable(), Money.ofKobo(5)));

		assertThatThrownBy(() -> JournalEntry.requireBalanced(lines)).hasMessage(
				"Debits of ₦0.10 and credits of ₦0.00 do not balance");
	}

	@Test
	void totalsThatOverflowFailInsteadOfWrapping() {
		List<PostingLine> lines = List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(Long.MAX_VALUE)),
				PostingLine.debit(AccountRef.vatPayable(), Money.ofKobo(1)),
				PostingLine.credit(AccountRef.platformFeeRevenue(), Money.ofKobo(1)));

		assertThatThrownBy(() -> JournalEntry.requireBalanced(lines)).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void aZeroLineIsNotAPosting() {
		assertThatThrownBy(() -> PostingLine.credit(AccountRef.vatPayable(), Money.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Posting.of(UUID.randomUUID(), UUID.randomUUID(), Direction.CREDIT, 0, NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/** Random amounts on both sides, then the smaller side topped up so the two totals match exactly. */
	private static List<PostingLine> balancedLines(Random random) {
		List<PostingLine> lines = new ArrayList<>();
		long debits = 0;
		long credits = 0;
		int count = 2 + random.nextInt(6);
		for (int i = 0; i < count; i++) {
			long amount = 1 + random.nextLong(10_000_000);
			Direction direction = i == 0 ? Direction.DEBIT : i == 1 ? Direction.CREDIT
					: random.nextBoolean() ? Direction.DEBIT : Direction.CREDIT;
			lines.add(new PostingLine(account(random), direction, Money.ofKobo(amount)));
			if (direction == Direction.DEBIT) {
				debits += amount;
			}
			else {
				credits += amount;
			}
		}
		if (debits != credits) {
			Direction shortSide = debits < credits ? Direction.DEBIT : Direction.CREDIT;
			lines.add(new PostingLine(account(random), shortSide, Money.ofKobo(Math.abs(debits - credits))));
		}
		return lines;
	}

	private static List<PostingLine> withOneKoboMore(List<PostingLine> lines, int index) {
		List<PostingLine> changed = new ArrayList<>(lines);
		PostingLine line = changed.get(index);
		changed.set(index, new PostingLine(line.account(), line.direction(), Money.ofKobo(line.amount().kobo() + 1)));
		return changed;
	}

	private static long total(List<PostingLine> lines, Direction direction) {
		return lines.stream().filter(line -> line.direction() == direction).mapToLong(line -> line.amount().kobo()).sum();
	}

	private static AccountRef account(Random random) {
		return ACCOUNTS.get(random.nextInt(ACCOUNTS.size()));
	}

}
