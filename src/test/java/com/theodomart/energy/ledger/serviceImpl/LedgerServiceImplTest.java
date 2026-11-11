package com.theodomart.energy.ledger.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.ledger.domain.JournalEntry;
import com.theodomart.energy.ledger.domain.LedgerAccount;
import com.theodomart.energy.ledger.repository.JournalEntryRepository;
import com.theodomart.energy.ledger.repository.LedgerAccountRepository;
import com.theodomart.energy.ledger.repository.PostingRepository;
import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.AccountType;
import com.theodomart.energy.ledger.service.EntryType;
import com.theodomart.energy.ledger.service.InsufficientWalletBalanceException;
import com.theodomart.energy.ledger.service.JournalEntryCommand;
import com.theodomart.energy.ledger.service.JournalEntryView;
import com.theodomart.energy.ledger.service.PostingLine;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LedgerServiceImplTest {

	private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");

	private final LedgerAccountRepository accounts = mock();

	private final JournalEntryRepository entries = mock();

	private final PostingRepository postings = mock();

	private final LedgerServiceImpl ledger = new LedgerServiceImpl(accounts, entries, postings,
			Clock.fixed(NOW, ZoneOffset.UTC));

	private final UUID tenant = UUID.randomUUID();

	private final UUID landlord = UUID.randomUUID();

	private LedgerAccount wallet;

	private LedgerAccount revenue;

	private LedgerAccount vat;

	private LedgerAccount fee;

	@BeforeEach
	void accountsExist() {
		wallet = account(AccountType.TENANT_WALLET, tenant);
		wallet.post(0, 500_000, NOW);
		revenue = account(AccountType.LANDLORD_REVENUE, landlord);
		vat = account(AccountType.VAT_PAYABLE, null);
		fee = account(AccountType.PLATFORM_FEE_REVENUE, null);
	}

	@Test
	void tenantGetsAWallet() {
		UUID newTenant = UUID.randomUUID();

		ledger.openTenantWallet(newTenant);

		verify(accounts).insertIfAbsent(any(), eq("TENANT"), eq(newTenant), eq("TENANT_WALLET"), eq("NGN"), eq(NOW));
	}

	@Test
	void landlordGetsARevenueAccount() {
		UUID newLandlord = UUID.randomUUID();

		ledger.openLandlordRevenueAccount(newLandlord);

		verify(accounts).insertIfAbsent(any(), eq("LANDLORD"), eq(newLandlord), eq("LANDLORD_REVENUE"), eq("NGN"),
				eq(NOW));
	}

	@Test
	void anAccountThatAlreadyExistsIsLeftAlone() {
		UUID existing = UUID.randomUUID();
		when(accounts.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0);

		ledger.openTenantWallet(existing);

		verify(accounts).insertIfAbsent(any(), eq("TENANT"), eq(existing), eq("TENANT_WALLET"), eq("NGN"), eq(NOW));
	}

	@Test
	void aPurchaseMovesEveryBalanceByItsLine() {
		JournalEntryView view = ledger.post(purchase("purchase-1"));

		assertThat(view.type()).isEqualTo(EntryType.ENERGY_PURCHASE);
		assertThat(view.idempotencyKey()).isEqualTo("purchase-1");
		assertThat(view.postedAt()).isEqualTo(NOW);
		assertThat(wallet.getBalanceKobo()).isEqualTo(300_000);
		assertThat(vat.getBalanceKobo()).isEqualTo(13_953);
		assertThat(fee.getBalanceKobo()).isEqualTo(3_720);
		assertThat(revenue.getBalanceKobo()).isEqualTo(182_327);
	}

	@Test
	void balancesAreWrittenAfterTheEntryAndInIdOrder() {
		ledger.post(purchase("purchase-2"));

		List<LedgerAccount> expectedOrder = List.of(wallet, revenue, vat, fee)
			.stream()
			.sorted(Comparator.comparing(account -> account.getId().toString()))
			.toList();
		InOrder order = inOrder(entries, postings, accounts);
		order.verify(entries).saveAndFlush(any(JournalEntry.class));
		order.verify(postings).saveAll(any());
		for (LedgerAccount account : expectedOrder) {
			order.verify(accounts).saveAndFlush(account);
		}
	}

	@Test
	void anAccountOnSeveralLinesIsUpdatedOnce() {
		ledger.post(new JournalEntryCommand(EntryType.ADJUSTMENT, "split", null, null, null,
				List.of(PostingLine.debit(AccountRef.tenantWallet(tenant), Money.ofKobo(100)),
						PostingLine.debit(AccountRef.tenantWallet(tenant), Money.ofKobo(50)),
						PostingLine.credit(AccountRef.vatPayable(), Money.ofKobo(150)))));

		verify(accounts).saveAndFlush(wallet);
		assertThat(wallet.getBalanceKobo()).isEqualTo(499_850);
	}

	@Test
	void anUnbalancedEntryIsRefusedBeforeAnythingIsWritten() {
		JournalEntryCommand unbalanced = new JournalEntryCommand(EntryType.ADJUSTMENT, "bad", null, null, null,
				List.of(PostingLine.debit(AccountRef.tenantWallet(tenant), Money.ofKobo(100)),
						PostingLine.credit(AccountRef.vatPayable(), Money.ofKobo(99))));

		assertThatThrownBy(() -> ledger.post(unbalanced)).isInstanceOf(IllegalArgumentException.class);
		verify(entries, never()).saveAndFlush(any());
		verify(accounts, never()).saveAndFlush(any());
	}

	@Test
	void aShortWalletStopsThePostingWithTheBalanceAndTheAmountNeeded() {
		JournalEntryCommand tooMuch = new JournalEntryCommand(EntryType.ENERGY_PURCHASE, "too-much", null, null, null,
				List.of(PostingLine.debit(AccountRef.tenantWallet(tenant), Money.ofKobo(600_000)),
						PostingLine.credit(AccountRef.landlordRevenue(landlord), Money.ofKobo(600_000))));

		assertThatThrownBy(() -> ledger.post(tooMuch)).isInstanceOf(InsufficientWalletBalanceException.class)
			.hasMessage("Wallet balance is ₦5,000.00 but ₦6,000.00 is needed.");
		verify(accounts, never()).saveAndFlush(wallet);
	}

	@Test
	void aMissingAccountIsABugNotAUserError() {
		UUID stranger = UUID.randomUUID();
		JournalEntryCommand command = new JournalEntryCommand(EntryType.ADJUSTMENT, "stranger", null, null, null,
				List.of(PostingLine.debit(AccountRef.vatPayable(), Money.ofKobo(1)),
						PostingLine.credit(AccountRef.tenantWallet(stranger), Money.ofKobo(1))));

		assertThatThrownBy(() -> ledger.post(command)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("TENANT_WALLET");
	}

	private JournalEntryCommand purchase(String key) {
		return new JournalEntryCommand(EntryType.ENERGY_PURCHASE, key, null, null, "Energy purchase",
				List.of(PostingLine.debit(AccountRef.tenantWallet(tenant), Money.ofKobo(200_000)),
						PostingLine.credit(AccountRef.vatPayable(), Money.ofKobo(13_953)),
						PostingLine.credit(AccountRef.platformFeeRevenue(), Money.ofKobo(3_720)),
						PostingLine.credit(AccountRef.landlordRevenue(landlord), Money.ofKobo(182_327))));
	}

	private LedgerAccount account(AccountType type, UUID owner) {
		LedgerAccount account = LedgerAccount.open(type, owner, NOW);
		if (owner == null) {
			when(accounts.findByTypeAndOwnerIdIsNull(type)).thenReturn(Optional.of(account));
		}
		else {
			when(accounts.findByTypeAndOwnerId(type, owner)).thenReturn(Optional.of(account));
		}
		return account;
	}

}
