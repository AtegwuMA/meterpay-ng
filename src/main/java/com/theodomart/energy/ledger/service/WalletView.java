package com.theodomart.energy.ledger.service;

import java.time.Instant;

/** A tenant's wallet. {@code updatedAt} is when money last moved, or when the wallet was opened. */
public record WalletView(long balanceKobo, String currency, Instant updatedAt) {

}
