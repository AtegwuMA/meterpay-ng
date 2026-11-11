package com.theodomart.energy.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class LedgerAccountTest {

	private static final Instant NOW = Instant.parse("2026-11-11T09:00:00.123456789Z");

	@Test
	void aNewWalletIsEmptyInNaira() {
		UUID tenant = UUID.randomUUID();

		LedgerAccount wallet = LedgerAccount.open(AccountType.TENANT_WALLET, tenant, NOW);

		assertThat(wallet.getId()).isNotNull();
		assertThat(wallet.getType()).isEqualTo(AccountType.TENANT_WALLET);
		assertThat(wallet.getOwnerType()).isEqualTo(OwnerType.TENANT);
		assertThat(wallet.getOwnerId()).isEqualTo(tenant);
		assertThat(wallet.getCurrency()).isEqualTo("NGN");
		assertThat(wallet.getBalanceKobo()).isZero();
		assertThat(wallet.getCreatedAt()).isEqualTo("2026-11-11T09:00:00.123456Z");
	}

	@ParameterizedTest
	@CsvSource({ "TENANT_WALLET, TENANT", "LANDLORD_REVENUE, LANDLORD", "GATEWAY_CLEARING, PLATFORM",
			"VAT_PAYABLE, PLATFORM", "PLATFORM_FEE_REVENUE, PLATFORM" })
	void eachTypeBelongsToTheOwnerInTheDesign(AccountType type, OwnerType owner) {
		assertThat(type.ownerType()).isEqualTo(owner);
	}

	@ParameterizedTest
	@EnumSource(value = AccountType.class, names = { "TENANT_WALLET", "LANDLORD_REVENUE" })
	void aPersonsAccountNeedsAnOwner(AccountType type) {
		assertThatThrownBy(() -> LedgerAccount.open(type, null, NOW)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("needs an owner");
	}

	@ParameterizedTest
	@EnumSource(value = AccountType.class, names = { "GATEWAY_CLEARING", "VAT_PAYABLE", "PLATFORM_FEE_REVENUE" })
	void aPlatformAccountHasNoOwner(AccountType type) {
		assertThatThrownBy(() -> LedgerAccount.open(type, UUID.randomUUID(), NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("no owner");
		assertThat(LedgerAccount.open(type, null, NOW).getOwnerId()).isNull();
	}

	@Test
	void accountsAreEqualByIdOnly() {
		UUID tenant = UUID.randomUUID();
		LedgerAccount wallet = LedgerAccount.open(AccountType.TENANT_WALLET, tenant, NOW);
		LedgerAccount sameOwner = LedgerAccount.open(AccountType.TENANT_WALLET, tenant, NOW);

		assertThat(wallet).isEqualTo(wallet).hasSameHashCodeAs(wallet).isNotEqualTo(sameOwner).isNotEqualTo("wallet");
		assertThat(wallet.toString()).contains(wallet.getId().toString()).contains("TENANT_WALLET");
	}

}
