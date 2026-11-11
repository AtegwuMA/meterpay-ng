package com.theodomart.energy.ledger.serviceImpl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.theodomart.energy.ledger.repository.LedgerAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerServiceImplTest {

	private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");

	private final LedgerAccountRepository accounts = mock();

	private final LedgerServiceImpl ledger = new LedgerServiceImpl(accounts, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void tenantGetsAWallet() {
		UUID tenant = UUID.randomUUID();

		ledger.openTenantWallet(tenant);

		verify(accounts).insertIfAbsent(any(), eq("TENANT"), eq(tenant), eq("TENANT_WALLET"), eq("NGN"), eq(NOW));
	}

	@Test
	void landlordGetsARevenueAccount() {
		UUID landlord = UUID.randomUUID();

		ledger.openLandlordRevenueAccount(landlord);

		verify(accounts).insertIfAbsent(any(), eq("LANDLORD"), eq(landlord), eq("LANDLORD_REVENUE"), eq("NGN"),
				eq(NOW));
	}

	@Test
	void anAccountThatAlreadyExistsIsLeftAlone() {
		UUID tenant = UUID.randomUUID();
		when(accounts.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0);

		ledger.openTenantWallet(tenant);

		verify(accounts).insertIfAbsent(any(), eq("TENANT"), eq(tenant), eq("TENANT_WALLET"), eq("NGN"), eq(NOW));
	}

}
