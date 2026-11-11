package com.theodomart.energy.ledger.serviceImpl;

import com.theodomart.energy.ledger.domain.AccountType;
import com.theodomart.energy.ledger.domain.LedgerAccount;
import com.theodomart.energy.ledger.repository.LedgerAccountRepository;
import com.theodomart.energy.ledger.service.LedgerService;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerServiceImpl implements LedgerService {

	private static final Logger log = LoggerFactory.getLogger(LedgerServiceImpl.class);

	private final LedgerAccountRepository accounts;

	private final Clock clock;

	public LedgerServiceImpl(LedgerAccountRepository accounts, Clock clock) {
		this.accounts = accounts;
		this.clock = clock;
	}

	// MANDATORY: run on its own, this would commit an account even if the registration around it then failed.
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void openTenantWallet(UUID tenantId) {
		open(AccountType.TENANT_WALLET, tenantId);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void openLandlordRevenueAccount(UUID landlordId) {
		open(AccountType.LANDLORD_REVENUE, landlordId);
	}

	private void open(AccountType type, UUID ownerId) {
		LedgerAccount account = LedgerAccount.open(type, ownerId, clock.instant());
		int opened = accounts.insertIfAbsent(account.getId(), account.getOwnerType().name(), account.getOwnerId(),
				type.name(), account.getCurrency(), account.getCreatedAt());
		if (opened == 1) {
			log.info("Ledger account opened: accountId={} type={} ownerId={}", account.getId(), type, ownerId);
		}
	}

}
