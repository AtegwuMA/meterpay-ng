# ADR-0002: Money and energy as integer minor units

- **Status:** Accepted
- **Date:** 2026-10-25
- **Related:** DESIGN §5.2 rule 5, §6.1, §6.4; plan M0-05, M0-08; ADR-0005 (rounding rules)

## Context

Every kobo must be traceable through the ledger, and every watt-hour through the meter credit
ledger (DESIGN §2). Totals must balance exactly: a trial balance that is off by 0.000001 is still
an imbalance.

- `double` and `float` cannot represent most decimal amounts exactly (`0.1 + 0.2 != 0.3`), and
  the error grows as values are added up.
- `BigDecimal` is exact but easy to misuse: `equals` compares scale (`2.0` is not `2.00`), every
  operation needs an explicit rounding mode, and scale can drift between Java, JSON and the
  database.
- Nigerian naira has a fixed minor unit, the kobo (1 naira = 100 kobo). Multi-currency is a
  non-goal for v1 (DESIGN §2).

## Decision

**Money is a whole number of kobo; energy is a whole number of watt-hours, both as `long`.**

- `Money` record (`common.domain`, M0-05): `long kobo`, **never negative**. The constructor rejects
  negative values. Debit or credit is recorded on the posting, not in the sign of the amount, and
  a wallet can never go below zero (DESIGN §6.3).
- `Energy` record: `long wh`, **signed**. Meter credit entries carry signed deltas, and a meter's
  credit can drop below zero between the last reading and the cutoff (DESIGN §7.2).
- Tariff rates are `long` kobo per kWh. VAT and fees are `long` basis points (750 = 7.5%).
- All arithmetic is integer arithmetic. Any division rounds explicitly by the rules in ADR-0005,
  never implicitly.
- Overflow throws (`Math.addExact` and friends) instead of wrapping silently.
- Postgres columns are `BIGINT`. API fields carry integer minor units (`amountKobo`, `energyWh`).
  Human-readable text such as `₦1,200.00` is produced only for messages, by `Money.format()`,
  using integer arithmetic.

**Enforcement.** ArchUnit rule 5 (M0-08) fails the build if any class outside `common` declares a
`double`, `Double`, `float`, `Float` or `BigDecimal` field. This is deliberately stricter than
DESIGN §5.2, which scopes the rule to money and energy fields. Deciding which fields count by their
names could be dodged by renaming a field, and no quantity in this system needs fractions (voltage
is stored in decivolts, power in whole watts).

## Alternatives considered

- **`double` naira.** Rejected: inexact by construction. Ledgers built on floating point drift and
  then fail reconciliation in ways that are hard to trace.
- **`BigDecimal` naira.** Exact, but every arithmetic call site needs a scale and a rounding mode,
  equality is scale-sensitive, and JSON serialisers disagree on formatting. The precision it offers
  beyond the kobo is never needed here.
- **Postgres `NUMERIC` columns.** Exact, but map to `BigDecimal` in Java and bring the same
  problems. `BIGINT` maps directly to `long`.
- **A money library (Joda-Money, JSR 354 Moneta).** Solves multi-currency, which is a non-goal,
  at the cost of a dependency and an API larger than the two records we need.

## Consequences

- Sums are exact, so ledger invariants (`total debits == total credits`, DESIGN §6.3) can be
  checked with `==`.
- The range is about 92 quadrillion naira, far beyond any real balance. Overflow can only come
  from a bug, and it surfaces as an exception instead of a wrong number.
- Clients must divide by 100 (money) or 1000 (kWh) to display values. This is documented in the
  OpenAPI schema (M8-05).
- Rounding must be decided wherever division happens, which is the point: ADR-0005 records each
  rule, and the pricing calculator (M2-05) is tested against the DESIGN §6.4 worked example.
- Supporting a currency with a different minor unit would need a currency-aware `Money`. That is
  acceptable while multi-currency remains a non-goal.

## Revisit when

- Multi-currency moves into scope, or
- a tariff or regulation requires fractions of a kobo or of a watt-hour.
