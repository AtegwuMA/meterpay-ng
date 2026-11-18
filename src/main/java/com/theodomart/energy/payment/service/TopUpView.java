package com.theodomart.energy.payment.service;

import java.time.Instant;
import java.util.UUID;

/**
 * A top-up as the tenant sees it. {@code status} is a string because other modules may not use payment.domain.
 *
 * @param authorizationUrl the page to pay on; null until Paystack has accepted the payment
 */
public record TopUpView(UUID intentId, String status, long amountKobo, String authorizationUrl, Instant createdAt) {

}
