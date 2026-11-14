# ADR-0003: Double-entry ledger instead of a balance column

- **Status:** Accepted
- **Date:** 2026-11-14
- **Related:** DESIGN §2, §6.2, §6.3, §6.4; plan M3-01 to M3-07; ADR-0002 (integer minor units), ADR-0007 (idempotency)

## Context

Money arrives from Paystack, sits in tenants' wallets, and leaves as energy purchases that split into VAT, the
platform fee and the landlord's share. Refunds and corrections will follow. Every kobo must be traceable (DESIGN §2):
asked why a wallet holds ₦3,000, we must be able to show every movement that made it so.

A purchase moves money between four parties at once, and many tenants buy at the same time. A bug that loses or
creates a kobo must be detectable, not just unlikely.

## Decision

**Money is recorded as balanced journal entries of immutable postings, and balances are running totals of them.**

- **Accounts** (`ledger_accounts`): one per owner and type (DESIGN §6.3), unique across `owner_type`, `owner_id` and
  `type`, with NULLS NOT DISTINCT so the platform's ownerless accounts cannot be duplicated.
- **Journal entries** (`journal_entries`): one per movement of money, with a type, an optional reference to what
  caused it, and a unique idempotency key (ADR-0007).
- **Postings** (`postings`): the lines of an entry, each a positive amount with a DEBIT or CREDIT direction.

The rules, and what enforces each one:

| Rule | Enforced by |
|---|---|
| Debits equal credits in every entry, with at least two lines | `JournalEntry.requireBalanced`, before anything is written; `LedgerInvariantTest` |
| Entries and postings never change | V12 trigger refusing UPDATE and DELETE; `@Immutable`; ArchUnit rule 4 |
| A wallet never goes below zero | `LedgerAccount.post`, under a row lock; a CHECK on the column |
| Posting is all or nothing | One transaction; the caller's when there is one |
| No deadlocks between postings | Accounts locked with `SELECT ... FOR UPDATE ORDER BY id`, then updated in the same order |
| The books balance, and each balance equals its postings | `GET /admin/ledger/trial-balance` and a scheduled check with two gauges |

`balance_kobo` is a cache kept for two reasons: reading a wallet is one row, and that row is what a posting locks.
The postings are the record. When they disagree, the postings are right, and the balance is fixed by a correcting
entry, never by editing it.

## Alternatives considered

- **A balance column on the wallet, updated in place.** Simple and fast, but it records the result and throws away the
  reasons. A wrong balance cannot be explained or proven wrong, and a purchase's VAT and fee have nowhere to go.
- **A single-entry transactions table.** Each row records one side of a movement. There is no rule that ties the sides
  together, so nothing proves the money that left the wallet arrived anywhere.
- **Postings only, balances computed on every read.** No cache to drift, but every wallet read sums all of its history,
  and there is no row to lock when two purchases race for the same money.
- **An external ledger (TigerBeetle, Formance).** Built for this at far greater scale, but a second system to run and
  keep consistent with ours. Not justified at this size, and less to learn from in a portfolio project.

## Consequences

- Every money feature starts by choosing its posting recipe (DESIGN §6.4). Code that moves money any other way is a bug.
- Mistakes are corrected with new entries, so history only grows, and an admin cannot quietly edit an amount.
- Every purchase locks the platform VAT and fee accounts, so purchases wait their turn on those rows. Throughput is
  bounded by how fast one posting commits, which is ample here.
- The drift check reads every posting, so its cost grows with history.
- Postgres 15 or later is required, for NULLS NOT DISTINCT (DESIGN §14).

## Revisit when

- Purchases queue noticeably on the platform accounts. Then stop keeping running totals on those accounts, or spread
  them across several rows.
- The drift check becomes slow. Then check incrementally from a stored checkpoint.
- Multi-currency moves into scope (ADR-0002).
