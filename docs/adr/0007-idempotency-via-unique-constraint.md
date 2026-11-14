# ADR-0007: Idempotency via database unique constraints

- **Status:** Accepted
- **Date:** 2026-11-14
- **Related:** DESIGN §6.2, §7.1, §8.1, §8.2, §10; plan M3-02, M3-04, M4; ADR-0003 (ledger)

## Context

Anything that moves money will be asked to happen more than once. A phone loses signal and retries. Paystack sends
the same webhook twice. Two instances run the same job. A tenant taps "buy" twice. Each must have the effect of
happening exactly once.

The usual first attempt, "look for an existing row, insert if there is none", races: two requests can both look,
both find nothing, and both insert. And in Postgres, a failed insert aborts the whole transaction it ran in, so simply
catching a duplicate is not enough when the transaction has more work to do.

## Decision

**Whatever must happen once has a key with a UNIQUE constraint, and the constraint decides. A lookup first is never
the guarantee.**

Two ways of using the constraint, chosen by what the transaction still has to do:

| Situation | Technique | Used for |
|---|---|---|
| The insert is the request's last step, and a duplicate ends it | Insert, catch the violation, map it by constraint name to a clear error | Meter serials, unit labels, one active tenancy per unit, open invites, user email and phone |
| The transaction must carry on after a duplicate | `INSERT ... ON CONFLICT DO NOTHING`, then read the row that won | Journal entries inside a purchase, accounts opened at registration, tariff plans, the V11 backfill |

Supporting rules:

- **Keys come from what is being paid for.** A top-up's entry key is `"topup:" + intent_id`, so a second webhook for
  the same payment maps to the same key and posts nothing. Client-facing money endpoints take an `Idempotency-Key`
  header, stored as SHA-256 of the tenant id and the header (M4), so two tenants can never collide.
- **A repeat must be the same request.** The ledger compares type, reference and lines, and anything different is
  422 `IDEMPOTENCY_KEY_REUSED`. Otherwise a reused key would hide a different request behind the first one's result.
- **A quick check first is allowed as a shortcut only**, such as the admin bootstrap skipping its work when an admin
  exists. Correctness still rests on the constraint.

## Alternatives considered

- **Check, then insert.** Races under concurrency, as described above. Rejected outright.
- **A distributed lock (Redis `SET NX`) around the operation.** A second system that must agree with the database; a
  lock can expire mid-operation; and it is not part of the database transaction, so a crash between the two leaves
  them disagreeing.
- **SERIALIZABLE isolation everywhere.** Correct, but every conflict becomes a serialization failure to retry, on
  every path, including the many with no race.
- **Savepoints, to catch a violation and carry on.** JPA has no clean support for them, and they add a hidden rollback
  point to every caller. ON CONFLICT does the same job in one statement.

## Consequences

- Every new table that records something which must happen once needs its unique key designed first.
- Paths that use ON CONFLICT must stay at READ COMMITTED. Under REPEATABLE READ, a conflict with a row committed after
  the snapshot is a serialization failure, and the follow-up read could not see the winner anyway.
- Keys are kept forever: a retry months later still returns the original result. Storage grows with them.
- Tested where it matters: concurrent retries of one journal entry post once; a repeat inside a caller's transaction
  leaves it usable; racing tenants for one invite get one tenancy; parallel first tariffs share one plan.

## Revisit when

- Keys need to expire, for example to bound storage. Then add a retention rule and decide what an expired key's retry
  should do.
- A second datastore takes part in the same operation, which a database constraint cannot cover.
