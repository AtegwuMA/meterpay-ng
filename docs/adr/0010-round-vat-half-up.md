# ADR-0010: Round VAT half up

- **Status:** Accepted
- **Date:** 2026-11-06
- **Related:** Supersedes the VAT rounding in ADR-0005. DESIGN §6.1, §6.4; plan M2-05

## Context

ADR-0005 rounds every division down, VAT included. VAT is money held for the tax authority in
`VAT_PAYABLE`, which is the platform's own account. Always rounding it down collects up to 1 kobo too
little on every purchase, and the shortfall only ever goes one way, so it grows with volume.

## Decision

**VAT rounds half up:** `(2 × gross × vat_bp + d) / (2 × d)`, where `d = 10000 + vat_bp`. An exact half goes
up. This is written with whole numbers so that an odd `d` cannot shift the point where a half falls.

Everything else in ADR-0005 stands: VAT and the fee come out of the amount paid, the fee still rounds down,
net is still a subtraction, and energy still rounds down last.

## Alternatives considered

- **Keep rounding down until a tax adviser reviews it.** Leaves a shortfall in one direction on every
  purchase in the meantime, and the change only gets harder once purchases exist.
- **Round half to even.** Slightly less biased on exact halves, but less familiar on a receipt. At 7.5% an
  exact half cannot occur at all, because `d` is 10750 and `gross × 750 / 10750` reduces to `gross × 3 / 43`.

## Consequences

- VAT on a purchase is within half a kobo of the exact figure, in either direction, so differences from a
  period's total tend to cancel out instead of building up.
- When VAT rounds up, net falls by that kobo, so the landlord and the energy bear it. Energy is calculated
  from net and rounded down last, so a tenant still never receives more than they paid for.
- The worked example in DESIGN §6.4 is unchanged (13953 either way). Small amounts change: the smallest
  purchase that buys any energy at the example's rates moves from 24 to 25 kobo.
- At an unrealistic 100% VAT, a purchase of 1 kobo is all VAT and leaves net at 0.
- Enforced by `PricingCalculatorImplTest`, which checks every amount from 1 to 20,000 kobo.

## Revisit when

- A tax professional reviews this. This choice is the normal commercial rule, not tax advice.
- A VAT invoice or filing rule requires rounding per invoice or per period.
