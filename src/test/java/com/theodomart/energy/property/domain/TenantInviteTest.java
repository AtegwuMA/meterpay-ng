package com.theodomart.energy.property.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantInviteTest {

	private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");

	private static final Duration TTL = Duration.ofDays(7);

	private final TenantInvite invite = TenantInvite.create(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), NOW,
			TTL);

	@Test
	void newInviteIsRedeemableUntilItExpires() {
		assertThat(invite.isRedeemable(NOW)).isTrue();
		assertThat(invite.isRedeemable(NOW.plus(TTL).minusSeconds(1))).isTrue();
		assertThat(invite.isRedeemable(NOW.plus(TTL))).isFalse();
		assertThat(invite.getExpiresAt()).isEqualTo(NOW.plus(TTL));
	}

	@Test
	void acceptingRecordsWhoAndWhenAndUsesItUp() {
		UUID tenant = UUID.randomUUID();

		invite.accept(tenant, NOW);

		assertThat(invite.getAcceptedBy()).isEqualTo(tenant);
		assertThat(invite.getAcceptedAt()).isEqualTo(NOW);
		assertThat(invite.isRedeemable(NOW)).isFalse();
	}

	@Test
	void usedOrExpiredInviteCannotBeAccepted() {
		assertThatThrownBy(() -> invite.accept(UUID.randomUUID(), NOW.plus(TTL)))
			.isInstanceOf(IllegalStateException.class);

		invite.accept(UUID.randomUUID(), NOW);

		assertThatThrownBy(() -> invite.accept(UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
	}

}
