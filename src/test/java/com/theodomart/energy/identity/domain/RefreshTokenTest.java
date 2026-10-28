package com.theodomart.energy.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

	private static final Instant NOW = Instant.parse("2026-10-27T08:00:00Z");

	private static final Duration TTL = Duration.ofDays(30);

	private final UUID userId = UUID.randomUUID();

	private final RefreshToken first = RefreshToken.startFamily(userId, "a".repeat(64), NOW, TTL);

	@Test
	void newFamilyStartsUsableForTheFullTtl() {
		assertThat(first.getId()).isNotNull();
		assertThat(first.getFamilyId()).isNotNull().isNotEqualTo(first.getId());
		assertThat(first.getExpiresAt()).isEqualTo(NOW.plus(TTL));
		assertThat(first.isUsable(NOW)).isTrue();
		assertThat(first.isRetired()).isFalse();
	}

	@Test
	void expiresAtTheEndOfItsTtl() {
		assertThat(first.isUsable(NOW.plus(TTL).minusSeconds(1))).isTrue();
		assertThat(first.isUsable(NOW.plus(TTL))).isFalse();
		assertThat(first.isRetired()).as("expired is not the same as retired").isFalse();
	}

	@Test
	void rotationRetiresTheOldTokenAndKeepsTheFamily() {
		Instant later = NOW.plus(Duration.ofDays(3));

		RefreshToken second = first.rotate("b".repeat(64), later, TTL);

		assertThat(first.isRetired()).isTrue();
		assertThat(first.getRevokedAt()).isEqualTo(later);
		assertThat(first.getReplacedBy()).isEqualTo(second.getId());
		assertThat(second.getFamilyId()).isEqualTo(first.getFamilyId());
		assertThat(second.getUserId()).isEqualTo(userId);
		assertThat(second.getExpiresAt()).as("a fresh lifetime from the rotation").isEqualTo(later.plus(TTL));
		assertThat(second.isUsable(later)).isTrue();
	}

	@Test
	void retiredOrExpiredTokenCannotBeRotated() {
		first.rotate("b".repeat(64), NOW, TTL);

		assertThatThrownBy(() -> first.rotate("c".repeat(64), NOW, TTL)).isInstanceOf(IllegalStateException.class);

		RefreshToken expired = RefreshToken.startFamily(userId, "d".repeat(64), NOW, TTL);
		assertThatThrownBy(() -> expired.rotate("e".repeat(64), NOW.plus(TTL), TTL))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void revokeIsIdempotentAndKeepsTheFirstTimestamp() {
		first.revoke(NOW);
		first.revoke(NOW.plusSeconds(60));

		assertThat(first.getRevokedAt()).isEqualTo(NOW);
		assertThat(first.isUsable(NOW)).isFalse();
	}

	@Test
	void toStringLeaksNoHash() {
		assertThat(first.toString()).doesNotContain("a".repeat(64));
	}

}
