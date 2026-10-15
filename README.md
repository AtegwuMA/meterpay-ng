# MeterPay

![CI](https://github.com/AtegwuMA/meterpay-ng/actions/workflows/ci.yml/badge.svg)
![Coverage](https://img.shields.io/badge/coverage-xx%25-brightgreen)
![Java](https://img.shields.io/badge/Java-21-blue)

Backend for a prepaid electricity sub-metering platform for multi-unit residential buildings. Served by a React landlord dashboard and a React Native tenant app (separate repositories).

**Live API docs:** https://meterpay.theodomart.com/swagger-ui.html

## The problem

In Nigeria, a single utility meter often supplies an entire block of flats. Landlords split the monthly bill by estimate, tenants dispute it, and the landlord ends up funding everyone's electricity in advance. Smart sub-meters solve the hardware side, but someone still has to sell units, track usage, collect payment and cut supply fairly.

MeterPay lets a landlord register buildings, units and meters and set a tariff. Tenants top up a wallet, buy electricity units, and supply is cut off automatically at zero credit and restored when they buy more.

## Architecture

<!-- Replace with exported diagram from docs/DESIGN.md §5 -->

Modular monolith (Spring Boot, Java 21) with PostgreSQL, Redis and Kafka.

| Module | Responsibility |
|---|---|
| identity | Users, JWT auth, rotating refresh tokens |
| property | Buildings, units, tenancies |
| meter | Meter registry and state machine |
| tariff | Versioned tariffs, VAT and fee calculation |
| ledger | Double-entry money ledger (kobo) |
| payment | Paystack top-ups, signed webhooks, reconciliation |
| vending | Buying energy, meter credit ledger (Wh) |
| telemetry | Kafka reading ingestion, consumption, cutoff |
| alerting | Low credit and tamper detection |
| audit | Hash-chained append-only audit log |

## Engineering highlights

- **Double-entry ledger.** No balance column is ever edited directly. Every naira is a balanced journal entry. ([ADR-0003](docs/adr/0003-double-entry-ledger.md))
- **Idempotent payments.** Duplicate requests and duplicate webhooks cannot double-charge or double-credit, enforced by database constraints. ([ADR-0007](docs/adr/0007-idempotency-via-unique-constraint.md))
- **Concurrency-safe wallets.** Twenty simultaneous purchases on one wallet never overdraw it, proven by `ConcurrentPurchaseIT`.
- **Transactional outbox.** Events reach Kafka only if the business transaction commits. ([ADR-0004](docs/adr/0004-transactional-outbox.md))
- **Two-layer supply enforcement.** Credit pushed to the meter plus a cloud-side cutoff. ([ADR-0006](docs/adr/0006-two-layer-credit-enforcement.md))
- **Tamper detection** on register rollback and consumption while cut off.
- **Tamper-evident audit log** using a SHA-256 hash chain.

## Run it locally

Requirements: Docker, JDK 21.

```bash
git clone git@github.com:AtegwuMA/meterpay-ng.git
cd meterpay-ng
cp .env.example .env
docker compose up
```

This starts Postgres, Redis, Kafka, the API and a meter simulator with demo data.

| Role | Email | Password |
|---|---|---|
| Landlord | landlord@demo.meterpay | (from seeder) |
| Tenant | tenant1@demo.meterpay | (from seeder) |

Swagger UI: http://localhost:8080/swagger-ui.html

## API overview

| Area | Endpoints |
|---|---|
| Auth | `POST /api/v1/auth/register`, `/login`, `/refresh`, `/logout` |
| Property | `/api/v1/buildings`, `/api/v1/buildings/{id}/units` |
| Meters | `/api/v1/meters`, `/api/v1/meters/{id}/readings` |
| Wallet | `GET /api/v1/wallet`, `POST /api/v1/wallet/top-ups` |
| Vending | `POST /api/v1/meters/{id}/purchases` |
| Alerts | `/api/v1/alerts` |
| Reports | `/api/v1/reports/buildings/{id}/consumption`, `/revenue` |

Errors follow RFC 9457 Problem Details with a stable `code` and `traceId`.

## Testing

```bash
./mvnw verify
```

- Unit tests for pricing, ledger invariants and state machines
- Integration tests on real Postgres, Redis and Kafka via Testcontainers
- Paystack contract tests with WireMock
- ArchUnit rules enforcing module boundaries
- Coverage gate: 75%

## Design decisions

See [`docs/DESIGN.md`](docs/DESIGN.md) and [`docs/adr/`](docs/adr).

## Author

Ategwu Martins Aburu, backend developer with an electrical engineering background. <!-- LinkedIn / portfolio link -->
