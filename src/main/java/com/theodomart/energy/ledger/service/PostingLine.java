package com.theodomart.energy.ledger.service;

import com.theodomart.energy.common.domain.Money;
import java.util.Objects;

/** One line of a journal entry. The amount is always positive: the direction says which way it moves. */
public record PostingLine(AccountRef account, Direction direction, Money amount) {

	public PostingLine {
		Objects.requireNonNull(account, "account");
		Objects.requireNonNull(direction, "direction");
		Objects.requireNonNull(amount, "amount");
		if (amount.isZero()) {
			throw new IllegalArgumentException("A posting must move more than zero");
		}
	}

	public static PostingLine debit(AccountRef account, Money amount) {
		return new PostingLine(account, Direction.DEBIT, amount);
	}

	public static PostingLine credit(AccountRef account, Money amount) {
		return new PostingLine(account, Direction.CREDIT, amount);
	}

}
