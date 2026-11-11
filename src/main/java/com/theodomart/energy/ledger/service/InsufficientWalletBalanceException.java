package com.theodomart.energy.ledger.service;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.common.error.DomainException;
import com.theodomart.energy.common.error.ErrorCode;

public class InsufficientWalletBalanceException extends DomainException {

	public InsufficientWalletBalanceException(Money balance, Money needed) {
		super(ErrorCode.INSUFFICIENT_WALLET_BALANCE,
				"Wallet balance is " + balance.format() + " but " + needed.format() + " is needed.");
	}

}
