package com.theodomart.energy.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theodomart.energy.ledger.service.AccountType;
import com.theodomart.energy.ledger.service.InsufficientWalletBalanceException;
import com.theodomart.energy.ledger.service.OwnerType;
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

	@Test
	void creditsGrowACreditNormalAccountAndDebitsShrinkIt() {
		LedgerAccount wallet = LedgerAccount.open(AccountType.TENANT_WALLET, UUID.randomUUID(), NOW);

		wallet.post(0, 500_000, NOW);
		wallet.post(200_000, 0, NOW);

		assertThat(wallet.getBalanceKobo()).isEqualTo(300_000);
	}

	@Test
	void debitsGrowTheClearingAccountBecauseItsNormalBalanceIsDebit() {
		LedgerAccount clearing = LedgerAccount.open(AccountType.GATEWAY_CLEARING, null, NOW);

		clearing.post(500_000, 0, NOW);
		clearing.post(0, 100_000, NOW);

		assertThat(clearing.getBalanceKobo()).isEqualTo(400_000);
	}

	@Test
	void aWalletCanBeSpentToExactlyZero() {
		LedgerAccount wallet = LedgerAccount.open(AccountType.TENANT_WALLET, UUID.randomUUID(), NOW);
		wallet.post(0, 1_000, NOW);

		wallet.post(1_000, 0, NOW);

		assertThat(wallet.getBalanceKobo()).isZero();
	}

	@Test
	void aWalletCannotGoBelowZeroAndIsLeftAsItWas() {
		LedgerAccount wallet = LedgerAccount.open(AccountType.TENANT_WALLET, UUID.randomUUID(), NOW);
		wallet.post(0, 120_000, NOW);

		assertThatThrownBy(() -> wallet.post(200_000, 0, NOW)).isInstanceOf(InsufficientWalletBalanceException.class)
			.hasMessage("Wallet balance is ₦1,200.00 but ₦2,000.00 is needed.");
		assertThat(wallet.getBalanceKobo()).isEqualTo(120_000);
	}

	@Test
	void aWalletCreditedAndDebitedInOneEntryIsJudgedOnTheResult() {
		LedgerAccount wallet = LedgerAccount.open(AccountType.TENANT_WALLET, UUID.randomUUID(), NOW);

		wallet.post(300, 500, NOW);

		assertThat(wallet.getBalanceKobo()).isEqualTo(200);
	}

	@Test
	void otherAccountsMayGoBelowZero() {
		LedgerAccount revenue = LedgerAccount.open(AccountType.LANDLORD_REVENUE, UUID.randomUUID(), NOW);

		revenue.post(700, 0, NOW);

		assertThat(revenue.getBalanceKobo()).isEqualTo(-700);
	}

	@Test
	void aBalanceThatWouldOverflowFailsInsteadOfWrapping() {
		LedgerAccount revenue = LedgerAccount.open(AccountType.LANDLORD_REVENUE, UUID.randomUUID(), NOW);
		revenue.post(0, Long.MAX_VALUE, NOW);

		assertThatThrownBy(() -> revenue.post(0, 1, NOW)).isInstanceOf(ArithmeticException.class);
	}

}
