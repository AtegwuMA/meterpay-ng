package com.theodomart.energy.ledger.repository;

import com.theodomart.energy.ledger.service.AccountType;

/** The sum of every balance of one account type. */
public record AccountTypeTotal(AccountType type, Long accounts, Long balanceKobo) {

}
