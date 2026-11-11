package com.theodomart.energy.ledger.serviceImpl;

import com.theodomart.energy.ledger.domain.JournalEntry;
import com.theodomart.energy.ledger.domain.LedgerAccount;
import com.theodomart.energy.ledger.domain.Posting;
import com.theodomart.energy.ledger.repository.JournalEntryRepository;
import com.theodomart.energy.ledger.repository.LedgerAccountRepository;
import com.theodomart.energy.ledger.repository.PostingRepository;
import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.AccountType;
import com.theodomart.energy.ledger.service.Direction;
import com.theodomart.energy.ledger.service.JournalEntryCommand;
import com.theodomart.energy.ledger.service.JournalEntryView;
import com.theodomart.energy.ledger.service.LedgerService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerServiceImpl implements LedgerService {

	private static final Logger log = LoggerFactory.getLogger(LedgerServiceImpl.class);

	/**
	 * The order every balance update follows. By the id as text, because that is how Postgres orders a UUID column:
	 * UUID.compareTo compares signed numbers and would disagree with an ORDER BY id for half of all ids.
	 */
	static final Comparator<LedgerAccount> UPDATE_ORDER = Comparator.comparing(account -> account.getId().toString());

	private final LedgerAccountRepository accounts;

	private final JournalEntryRepository entries;

	private final PostingRepository postings;

	private final Clock clock;

	public LedgerServiceImpl(LedgerAccountRepository accounts, JournalEntryRepository entries,
			PostingRepository postings, Clock clock) {
		this.accounts = accounts;
		this.entries = entries;
		this.postings = postings;
		this.clock = clock;
	}

	@Override
	@Transactional
	public JournalEntryView post(JournalEntryCommand command) {
		Instant now = clock.instant();
		JournalEntry entry = JournalEntry.create(command.type(), command.idempotencyKey(), command.referenceType(),
				command.referenceId(), command.description(), command.lines(), now);

		Map<AccountRef, LedgerAccount> byRef = new LinkedHashMap<>();
		Map<LedgerAccount, long[]> totals = new LinkedHashMap<>();
		List<Posting> lines = command.lines().stream().map(line -> {
			LedgerAccount account = byRef.computeIfAbsent(line.account(), this::load);
			long[] debitsAndCredits = totals.computeIfAbsent(account, ignored -> new long[2]);
			int side = line.direction() == Direction.DEBIT ? 0 : 1;
			debitsAndCredits[side] = Math.addExact(debitsAndCredits[side], line.amount().kobo());
			return Posting.of(entry.getId(), account.getId(), line.direction(), line.amount().kobo(), now);
		}).toList();

		// The entry and its postings are written first, while no account has changed: a flush writes every pending
		// change, so flushing them later would also write the balances, in whatever order Hibernate holds them.
		entries.saveAndFlush(entry);
		postings.saveAll(lines);

		// Balances one at a time in a fixed order. Each UPDATE holds its row until commit, so two entries that touch
		// the same accounts in opposite orders could otherwise deadlock.
		for (LedgerAccount account : totals.keySet().stream().sorted(UPDATE_ORDER).toList()) {
			long[] debitsAndCredits = totals.get(account);
			account.post(debitsAndCredits[0], debitsAndCredits[1], now);
			accounts.saveAndFlush(account);
		}
		log.info("Journal entry posted: entryId={} type={} lines={}", entry.getId(), entry.getEntryType(),
				lines.size());
		return new JournalEntryView(entry.getId(), entry.getEntryType(), entry.getIdempotencyKey(),
				entry.getReferenceType(), entry.getReferenceId(), entry.getPostedAt());
	}

	private LedgerAccount load(AccountRef ref) {
		return (ref.ownerId() == null ? accounts.findByTypeAndOwnerIdIsNull(ref.type())
				: accounts.findByTypeAndOwnerId(ref.type(), ref.ownerId()))
			// Every user gets their accounts at registration and the platform's are seeded, so this is a bug.
			.orElseThrow(() -> new IllegalStateException("No " + ref.type() + " account for owner " + ref.ownerId()));
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
