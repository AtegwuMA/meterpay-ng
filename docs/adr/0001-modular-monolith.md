# ADR-0001: Modular monolith on Spring Boot 4.1 and Java 21

- **Status:** Accepted
- **Date:** 2026-10-25
- **Related:** DESIGN §4, §5, §8.2; plan M0-01, M0-08

## Context

MeterPay moves money and energy. The most important flow, buying energy (DESIGN §8.2), must
debit a wallet, record the purchase, credit the meter and write an outbox event as one atomic
change. If any part fails, none of it may happen.

The system is built by one developer in about twelve weeks and is deployed to a single Oracle
Always Free VM. The expected load is small: buildings with tens of units, readings every few
minutes per meter.

The code still needs clear internal boundaries. Ledger, payment, vending and telemetry have very
different rules, and a reviewer should be able to see that one module cannot reach into another.

## Decision

**One deployable Spring Boot application, split into feature modules** (`identity`, `property`,
`meter`, `tariff`, `ledger`, `payment`, `vending`, `telemetry`, `alerting`, `audit`, `reporting`,
`outbox`, `gateway`, `security`) plus a shared `common` package. Layout per DESIGN §5.1.

- Modules talk to each other only through their `service` interfaces, or asynchronously through
  domain events that leave via the transactional outbox (ADR-0004).
- Boundaries are enforced by ArchUnit tests that run in every build (DESIGN §5.2, M0-08), not by
  convention.
- One PostgreSQL database. Each module owns its tables and no module queries another module's
  tables directly.
- Kafka is used where asynchrony is real: meter telemetry ingestion and events leaving a module.
  It is not used for request and response calls between modules.

**Platform versions**, chosen at M0-01 on 2026-10-15:

| Choice | Version | Why |
|---|---|---|
| Java | 21 | Current LTS. Records, sealed types and pattern matching are used throughout. |
| Spring Boot | 4.1.1 | Latest stable release on Spring Initializr at project start. |
| springdoc-openapi | 3.1.1 | The 3.x line is the one that supports Spring Boot 4. |
| Testcontainers | 2.0.5 | Managed by the Spring Boot BOM, not pinned separately. |

Spring Boot 4 split test auto-configuration into modules, so slice tests need their own starter
(for example `spring-boot-starter-webmvc-test` for `@WebMvcTest`). Patch upgrades are taken
freely. Minor and major upgrades get their own commit and a full `./mvnw verify`.

## Alternatives considered

- **Microservices.** The purchase flow would become a distributed transaction across wallet,
  vending and meter services, needing a saga with compensation logic for every failure. That is a
  large amount of correctness work for no benefit at this scale, and one VM cannot comfortably run
  a service mesh, several JVMs and their databases.
- **Maven multi-module build.** Gives compile-time boundaries, which are stronger than ArchUnit
  tests. Rejected for now because it adds build and wiring overhead to every task while the module
  list is still settling. ArchUnit catches the same violations, one build later.
- **Spring Modulith.** Offers module verification and event publication out of the box. Not
  chosen because it is not in the DESIGN §4 stack and the outbox is a deliberate learning and
  portfolio goal (ADR-0004). A reasonable later migration.
- **Spring Boot 3.5.** Mature and widely documented. Rejected because a new project with a
  multi-year horizon should not start on the previous major line.

## Consequences

- The purchase flow is a single local ACID transaction. No sagas, no two-phase commit.
- One artefact to build, deploy and monitor, which suits a single VM and a solo developer.
- Boundaries are only as strong as the ArchUnit rules. A rule gap is an architecture gap, so the
  rules are themselves tested against deliberately broken fixtures (M0-08).
- Shared database discipline is required: a cross-module SQL join would bypass every rule.
  Reporting (M8-03) needs aggregates across modules, so how it reads them must be decided there
  explicitly, not by quietly joining another module's tables.
- The whole application scales as one unit. Telemetry ingestion cannot scale independently yet.
- Because modules already talk through interfaces and events, extracting one later (telemetry is
  the likely first) is a refactor, not a rewrite.

## Revisit when

- Telemetry volume needs scaling independently of the API, or
- a second team owns part of the system, or
- deploying one module forces downtime on unrelated ones.
