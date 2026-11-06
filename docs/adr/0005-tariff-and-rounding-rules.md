# ADR-0005: Tariff, VAT and fee rounding rules

- **Status:** Accepted, except the VAT rounding: superseded by ADR-0010
- **Date:** 2026-11-06
- **Related:** DESIGN §6.1, §6.2, §6.4, §8.2, §16; plan M2-04, M2-04a, M2-05, M5-03; ADR-0002

## Context

A tenant pays a whole number of kobo and receives a whole number of watt-hours. Between the two sit
VAT, the platform fee and the landlord's price, and none of the divisions come out even. ADR-0002
makes all of this integer arithmetic, so every division needs a stated rule.

The journal entry for a purchase must balance exactly (DESIGN §6.3). The result must also be
reproducible later, and the rates change over time: VAT by law, the fee and the landlord's price by
choice.

## Decision

**VAT and the fee come out of the amount the tenant pays.** A tenant who buys ₦2,000 has ₦2,000 taken
from their wallet, and VAT and the fee reduce the energy that buys. The landlord's rate is what the
landlord receives per kWh, not what the tenant pays per kWh.

**The calculation, in this order, with every division rounding down:**

| Step | Formula |
|---|---|
| VAT | `gross × vat_bp / (10000 + vat_bp)` |
| Fee | `(gross − vat) × fee_bp / 10000` |
| Net | `gross − vat − fee` |
| Energy (Wh) | `net × 1000 / rate_kobo_per_kwh` |

- VAT is inclusive, so it is 750/10750 of the gross, not 7.5% of it.
- The fee is charged on the amount after VAT, so the platform takes no fee on tax.
- Net is a subtraction, not a third division, so the three parts always add up to the gross.
- Each multiplication comes before its division, so nothing rounds early, and overflow throws
  (ADR-0002).

**Where the rates come from.** VAT comes from `tax_rates`, and the fee and rate from the tariff version.
Both are read at one reading of the clock when the purchase is priced, so a purchase that spans midnight
uses the rates from when it was priced, not when it commits. Each purchase stores its amounts, the VAT
rate and the tariff version, so history does not change when rates do.

**Who keeps each rounded fraction** (each under one unit per purchase):

| Rounding | Effect | Who gains |
|---|---|---|
| VAT, under 1 kobo | Stays in the amount after VAT | Mostly the landlord |
| Fee, under 1 kobo | Stays in net | The landlord |
| Energy, under 1 Wh | Paid for and not delivered | The landlord |

The tenant never receives more energy than they paid for, which is what DESIGN §6.1 means by rounding
toward the platform. The platform's own revenue gains almost nothing: only its fee on the VAT fraction.

## Alternatives considered

- **VAT and fee charged on top.** The tenant asks for ₦2,000 and pays more, and a wallet holding exactly
  ₦2,000 could not buy ₦2,000. Rejected: the amount typed should be the amount taken, and tenants buy by
  how much money they have.
- **Landlord absorbs VAT and the fee, so the rate is what the tenant pays.** Tenants would see a simple
  price, but landlords would receive less than the rate they set, and a VAT rise would cut their income
  without their doing anything. Rejected for v1.
- **Round to nearest.** Closer on average, but it can deliver more energy than was paid for, and three
  separately rounded parts need not add up to the gross.
- **Fee on the gross.** Charges a fee on money that is only passing through to the tax authority.
- **Carrying fractions forward between purchases.** More accurate over time, but one purchase could no
  longer be recalculated from its own row, and it needs mutable state per tenant for under one kobo.

## Consequences

- `vat + fee + net == gross` by construction, so this calculation cannot unbalance a journal entry.
- The price per kWh a tenant pays is higher than the landlord's rate. In the DESIGN §6.4 example, ₦2,000
  at ₦225/kWh buys 8.103 kWh, about ₦246.82/kWh. Clients must show the energy, not only the rate, or
  tenants will think they were shorted. Nothing shows it before purchase yet.
- A VAT rise reduces the energy a tenant gets for the same money. The landlord's income per kWh does not
  change.
- Rounding VAT down on each purchase can collect up to 1 kobo less per purchase than VAT worked out on a
  period's total: at most ₦10,000 across a million purchases.
- Very small amounts round to zero. VAT and the fee can be 0, and those lines must be left out of the
  journal entry because `postings.amount_kobo > 0`. Energy can be 0 too, so M5-03 needs a minimum amount
  (DESIGN §16).
- Enforced by `PricingCalculatorImplTest`: hand-worked cases, and a check across every amount from 1 to
  20,000 kobo that the parts add up, each division is a true floor, and paying more never delivers less.

## Revisit when

- A tax professional reviews this. These rules have not had that review, and per-purchase rounding of VAT
  is the first thing to check.
- A VAT invoice or filing rule requires rounding per invoice or per period.
- Landlords want to set the price tenants pay, or tenants find the difference from the rate confusing.
