package com.theodomart.energy.ledger.service;

import java.time.Instant;
import java.util.List;

/**
 * The whole ledger at one moment (DESIGN 6.3). Balanced means two separate things both hold: the debit-normal and
 * credit-normal totals are equal, and every account's stored balance equals the sum of its own postings.
 *
 * @param imbalanceKobo debit-normal total minus credit-normal total; zero when the books balance
 * @param accountsNotMatchingPostings accounts whose running total has drifted from their postings
 */
public record TrialBalanceView(List<Line> lines, long debitNormalTotalKobo, long creditNormalTotalKobo,
		long imbalanceKobo, long accountsNotMatchingPostings, boolean balanced, Instant generatedAt) {

	public TrialBalanceView {
		lines = List.copyOf(lines);
	}

	public record Line(AccountType type, Direction normalBalance, long accounts, long balanceKobo) {

	}

}
