package com.theodomart.energy.ledger.serviceImpl;

import com.theodomart.energy.common.error.IdempotencyConflictException;
import com.theodomart.energy.ledger.domain.JournalEntry;
import com.theodomart.energy.ledger.domain.LedgerAccount;
import com.theodomart.energy.ledger.domain.Posting;
import com.theodomart.energy.ledger.repository.AccountTypeTotal;
import com.theodomart.energy.ledger.repository.JournalEntryRepository;
import com.theodomart.energy.ledger.repository.LedgerAccountRepository;
import com.theodomart.energy.ledger.repository.PostingRepository;
import com.theodomart.energy.ledger.repository.WalletLine;
import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.AccountType;
import com.theodomart.energy.ledger.service.Direction;
import com.theodomart.energy.ledger.service.JournalEntryCommand;
import com.theodomart.energy.ledger.service.JournalEntryView;
import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.TrialBalanceView;
import com.theodomart.energy.ledger.service.WalletTransactionView;
import com.theodomart.energy.ledger.service.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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

	/** Attempts in all, the first included, when an account changed under us (plan M3-05). */
	static final int MAX_ATTEMPTS = 3;

	/** The pause before attempt n is random, up to n times this, so racing retries spread out instead of colliding again. */
	private static final long RETRY_PAUSE_STEP_MILLIS = 25;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public LedgerServiceImpl(LedgerAccountRepository accounts, JournalEntryRepository entries,
			PostingRepository postings, Clock clock, PlatformTransactionManager transactionManager) {
		this.accounts = accounts;
		this.entries = entries;
		this.postings = postings;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * Accounts are locked, so postings over the same accounts wait their turn rather than fail. Called on its own,
	 * each attempt is a transaction of its own, and an attempt that still fails on concurrency (a lost optimistic
	 * check or a deadlock the database broke) is tried again, up to MAX_ATTEMPTS in all. Called inside a caller's transaction there is no retry here: the failed
	 * attempt has already spoiled that transaction, so the caller must retry its whole unit of work (M5-03 does).
	 */
	@Override
	public JournalEntryView post(JournalEntryCommand command) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return postOnce(command);
		}
		for (int attempt = 1;; attempt++) {
			try {
				return transactions.execute(status -> postOnce(command));
			}
			catch (ConcurrencyFailureException ex) {
				if (attempt == MAX_ATTEMPTS) {
					log.warn("Journal entry gave up after {} attempts: key={}", attempt, command.idempotencyKey());
					throw ex;
				}
				log.info("Journal entry lost a race on an account, retrying: attempt={} key={}", attempt,
						command.idempotencyKey());
				pauseBefore(attempt + 1);
			}
		}
	}

	// REPEATABLE_READ: the totals and the drift count come from two queries, and must describe the same moment, or a
	// posting committing between them could make a healthy ledger look broken.
	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public TrialBalanceView trialBalance() {
		Map<AccountType, AccountTypeTotal> byType = new HashMap<>();
		accounts.totalsByType().forEach(total -> byType.put(total.type(), total));
		long debitNormal = 0;
		long creditNormal = 0;
		List<TrialBalanceView.Line> lines = new java.util.ArrayList<>();
		// Every type is listed, even with no accounts yet, so a missing platform account shows as a zero line.
		for (AccountType type : Arrays.asList(AccountType.values())) {
			AccountTypeTotal total = byType.get(type);
			long count = total == null ? 0 : total.accounts();
			long balance = total == null || total.balanceKobo() == null ? 0 : total.balanceKobo();
			lines.add(new TrialBalanceView.Line(type, type.normalBalance(), count, balance));
			if (type.normalBalance() == Direction.DEBIT) {
				debitNormal = Math.addExact(debitNormal, balance);
			}
			else {
				creditNormal = Math.addExact(creditNormal, balance);
			}
		}
		long imbalance = Math.subtractExact(debitNormal, creditNormal);
		long mismatched = accounts.countNotMatchingPostings();
		return new TrialBalanceView(lines, debitNormal, creditNormal, imbalance, mismatched,
				imbalance == 0 && mismatched == 0, clock.instant());
	}

	@Override
	@Transactional(readOnly = true)
	public WalletView wallet(UUID tenantId) {
		LedgerAccount wallet = walletOf(tenantId);
		return new WalletView(wallet.getBalanceKobo(), wallet.getCurrency(), wallet.getUpdatedAt());
	}

	@Override
	@Transactional(readOnly = true)
	public Page<WalletTransactionView> walletTransactions(UUID tenantId, int page, int size) {
		LedgerAccount wallet = walletOf(tenantId);
		return postings.findHistory(wallet.getId(), PageRequest.of(page, size)).map(LedgerServiceImpl::toWalletView);
	}

	private LedgerAccount walletOf(UUID tenantId) {
		return accounts.findByTypeAndOwnerId(AccountType.TENANT_WALLET, tenantId)
			// Every tenant gets a wallet at registration, and V11 backfilled earlier ones, so this is a bug.
			.orElseThrow(() -> new IllegalStateException("Tenant " + tenantId + " has no wallet"));
	}

	private static WalletTransactionView toWalletView(WalletLine line) {
		long net = line.netCreditKobo();
		return new WalletTransactionView(line.entryId(), line.type(), net >= 0 ? Direction.CREDIT : Direction.DEBIT,
				Math.abs(net), line.referenceType(), line.referenceId(), line.description(), line.postedAt());
	}

	private static void pauseBefore(int attempt) {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(RETRY_PAUSE_STEP_MILLIS * attempt));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting to retry a journal entry", ex);
		}
	}

	private JournalEntryView postOnce(JournalEntryCommand command) {
		Instant now = clock.instant();
		JournalEntry entry = JournalEntry.create(command.type(), command.idempotencyKey(), command.referenceType(),
				command.referenceId(), command.description(), command.lines(), now);

		// Ids first, without loading anything, then every account locked in id order, and only then are balances
		// read. Reading before locking would let a posting that waited decide on a balance that has since changed.
		Map<AccountRef, UUID> idByRef = new LinkedHashMap<>();
		command.lines().forEach(line -> idByRef.computeIfAbsent(line.account(), this::resolveId));
		Map<UUID, LedgerAccount> lockedById = new HashMap<>();
		accounts.lockAllInIdOrder(new HashSet<>(idByRef.values()))
			.forEach(account -> lockedById.put(account.getId(), account));

		Map<LedgerAccount, long[]> totals = new LinkedHashMap<>();
		List<Posting> lines = command.lines().stream().map(line -> {
			LedgerAccount account = lockedById.get(idByRef.get(line.account()));
			long[] debitsAndCredits = totals.computeIfAbsent(account, ignored -> new long[2]);
			int side = line.direction() == Direction.DEBIT ? 0 : 1;
			debitsAndCredits[side] = Math.addExact(debitsAndCredits[side], line.amount().kobo());
			return Posting.of(entry.getId(), account.getId(), line.direction(), line.amount().kobo(), now);
		}).toList();

		// The key is claimed first, while no account has changed. If it was already taken this is a retry: return the
		// entry that won and touch nothing. A concurrent post with the same key waits here until the first commits.
		int inserted = entries.insertIfAbsent(entry.getId(), entry.getEntryType().name(), entry.getReferenceType(),
				entry.getReferenceId(), entry.getIdempotencyKey(), entry.getDescription(), entry.getPostedAt(),
				entry.getCreatedAt());
		if (inserted == 0) {
			return replay(command, lines);
		}
		postings.saveAll(lines);

		// Balances one at a time, in the same order the rows were locked. Each flush writes only this one account,
		// because no other account has changed yet. @Version stays as a backstop if a row was ever changed unlocked.
		for (LedgerAccount account : totals.keySet().stream().sorted(UPDATE_ORDER).toList()) {
			long[] debitsAndCredits = totals.get(account);
			account.post(debitsAndCredits[0], debitsAndCredits[1], now);
			accounts.saveAndFlush(account);
		}
		log.info("Journal entry posted: entryId={} type={} lines={}", entry.getId(), entry.getEntryType(),
				lines.size());
		return toView(entry);
	}

	/**
	 * The same key again. It is only a retry if it asks for exactly the same money movement: the same type, the same
	 * reference and the same lines. The description is for people and is not compared.
	 */
	private JournalEntryView replay(JournalEntryCommand command, List<Posting> requested) {
		JournalEntry existing = entries.findByIdempotencyKey(command.idempotencyKey())
			.orElseThrow(() -> new IllegalStateException("Idempotency key was taken but no entry has it"));
		boolean sameMovement = existing.getEntryType() == command.type()
				&& Objects.equals(existing.getReferenceType(), command.referenceType())
				&& Objects.equals(existing.getReferenceId(), command.referenceId())
				&& lineKeys(postings.findByJournalEntryId(existing.getId())).equals(lineKeys(requested));
		if (!sameMovement) {
			log.warn("Idempotency key reused for a different entry: entryId={}", existing.getId());
			throw new IdempotencyConflictException();
		}
		log.info("Journal entry replayed: entryId={}", existing.getId());
		return toView(existing);
	}

	/** Lines as comparable values, sorted, so the order a caller lists them in does not matter. */
	private static List<String> lineKeys(List<Posting> lines) {
		return lines.stream()
			.map(line -> line.getAccountId() + ":" + line.getDirection() + ":" + line.getAmountKobo())
			.sorted()
			.toList();
	}

	private static JournalEntryView toView(JournalEntry entry) {
		return new JournalEntryView(entry.getId(), entry.getEntryType(), entry.getIdempotencyKey(),
				entry.getReferenceType(), entry.getReferenceId(), entry.getPostedAt());
	}

	private UUID resolveId(AccountRef ref) {
		return (ref.ownerId() == null ? accounts.findIdByTypeAndOwnerIdIsNull(ref.type())
				: accounts.findIdByTypeAndOwnerId(ref.type(), ref.ownerId()))
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
