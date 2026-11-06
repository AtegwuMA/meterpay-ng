# MeterPay: Implementation Plan

Build order for the backend described in `docs/DESIGN.md`. Every task is sized to be **one commit**. Work top to bottom. Do not start a task until the one before it is committed and green.

Target: 12 weeks, mid-October 2026 to mid-January 2027, roughly 4 to 6 tasks a week.

**How to use this file with Claude Code**

```
Implement task M1-03 from docs/IMPLEMENTATION_PLAN.md
```

Claude Code implements that one task, runs `./mvnw verify`, commits it to `main` with the message below (ticking the box here in the same commit), and stops. You review it and say when to push. Nothing is pushed without that.

---

## M0: Foundation (week 1)

- [x] **M0-01 Project skeleton.** Spring Boot app via Maven wrapper, Java 21, base package `com.theodomart.energy`, dependencies: web, validation, data-jpa, security, actuator, flyway, postgresql, data-redis, kafka, springdoc, lombok (optional). `.gitignore`, `.editorconfig`.
  - Done when: `./mvnw verify` passes with a context-loads test.
  - Commit: `chore: bootstrap spring boot project`
- [x] **M0-02 Local infrastructure.** `docker-compose.yml` with Postgres 16, Redis 7, Kafka (KRaft). `application.yml` + `application-local.yml`. `.env.example`.
  - Done when: `docker compose up -d` then `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` starts and `/actuator/health` is UP.
  - Commit: `chore: add docker compose for postgres, redis and kafka`
- [x] **M0-03 Flyway baseline.** `V1__baseline.sql` (extensions only: `pgcrypto`). `ddl-auto=validate` in all profiles.
  - Commit: `chore(db): add flyway baseline migration`
- [x] **M0-04 Testcontainers base.** Abstract `IntegrationTest` with singleton Postgres, Redis, Kafka containers and `@ServiceConnection`. Maven Surefire for `*Test`, Failsafe for `*IT`.
  - Done when: a sample IT starts all three containers and passes.
  - Commit: `test: add testcontainers integration test base`
- [x] **M0-05 Common value types.** `Money` (kobo, non-negative factory, add, subtract, format as ₦) and `Energy` (Wh). Full unit tests including overflow and negative cases.
  - Commit: `feat(common): add Money and Energy value types`
- [x] **M0-06 Global error handling.** `GlobalExceptionHandler` producing ProblemDetail per DESIGN §10 with `code` and `traceId`. Base `DomainException` with `ErrorCode` enum.
  - Done when: `@WebMvcTest` covers 400 validation, 404, 409, 500 bodies.
  - Commit: `feat(common): add RFC 9457 problem details error handling`
- [x] **M0-07 Correlation ID.** Filter reading or generating `X-Correlation-Id`, MDC, response header. Logback pattern includes it.
  - Commit: `feat(common): propagate correlation id through requests and logs`
- [x] **M0-08 ArchUnit rules.** Rules 1 to 5 from DESIGN §5.2 (rules that have no classes yet pass trivially with `allowEmptyShould(true)`).
  - Commit: `test(arch): enforce module and service layer boundaries`
- [x] **M0-09 CI pipeline.** GitHub Actions: JDK 21, Maven cache, `./mvnw verify`, upload JaCoCo report. Spotless check. JaCoCo rule (start at 60%, raised to 75% in M8).
  - Commit: `ci: add github actions build with coverage report`
- [x] **M0-10 ADR scaffolding.** `docs/adr/0000-template.md`, ADR-0001 (modular monolith, Spring Boot version chosen) and ADR-0002 (integer minor units).
  - Commit: `docs(adr): record modular monolith and integer money decisions`

## M1: Identity and property (week 2)

- [x] **M1-01 Users table and entity.** `V2__users.sql`, `User` entity, roles, repository. Repository IT checks unique email/phone constraints.
  - Commit: `feat(identity): add user entity and migration`
- [x] **M1-02 Registration and login.** `POST /auth/register` (landlord only for now), `POST /auth/login`, BCrypt, JWT access token issuance and filter, `GET /me`.
  - Done when: IT registers, logs in, calls `/me`; wrong password gives 401 problem detail.
  - Commit: `feat(identity): add registration, login and jwt authentication`
- [x] **M1-03 Refresh token rotation.** `refresh_tokens` table, rotate on use, family revocation on reuse, logout.
  - Done when: test proves reusing a rotated token revokes the family.
  - Commit: `feat(identity): rotate refresh tokens with reuse detection`
- [x] **M1-04 Buildings and units.** Migrations, entities, CRUD endpoints for landlord. Pagination.
  - Commit: `feat(property): add buildings and units endpoints`
- [x] **M1-05 Ownership enforcement.** `OwnershipService`; another landlord's building returns 404.
  - Done when: IT with two landlords proves isolation.
  - Commit: `feat(security): enforce landlord ownership on property resources`
- [x] **M1-06 Tenant invites and tenancy.** Invite code per unit, tenant registration with code, `tenancies` with one-active-per-unit partial index, end tenancy.
  - Commit: `feat(property): add tenant invites and tenancy lifecycle`

## M2: Meters and tariffs (week 3)

- [x] **M2-01 Meter registry.** `meters` table and entity, register, assign to unit, get. Serial number unique.
  - Commit: `feat(meter): add meter registration and unit assignment`
- [x] **M2-02 Meter state machine.** `MeterStatus` with allowed transitions in one place, `IllegalStateTransitionException`. Exhaustive parameterized unit test of every transition pair.
  - Commit: `feat(meter): add meter state machine with guarded transitions`
- [x] **M2-03 Suspend and resume.** Endpoints, restore previous state.
  - Commit: `feat(meter): allow landlords to suspend and resume meters`
- [x] **M2-04 Tariff plans and versions.** Immutable versions with `effective_from`, active version lookup.
  - Done when: tests cover future-dated version not yet active.
  - Commit: `feat(tariff): add versioned tariff plans per building`
- [x] **M2-04a Platform VAT rates.** Move VAT from tariff versions to an immutable `tax_rates` table, seeded with the current rate. `findActivePricing` reads the version and the rate at one moment (DESIGN §6.2, §8.2).
  - Commit: `refactor(tariff): move vat from tariff versions to platform tax rates`
- [x] **M2-05 Pricing calculator.** Pure function: gross kobo + tariff version -> vat, fee, net, Wh. Implements DESIGN §6.4 rounding. Table-driven tests including the worked example.
  - Commit: `feat(tariff): calculate vat, platform fee and energy from amount`
- [x] **M2-06 ADR-0005.** Rounding and VAT decision.
  - Commit: `docs(adr): record tariff and rounding rules`
- [x] **M2-07 Tenant's tenancies.** `GET /me/tenancies` in the `meter` module (DESIGN §17.4): active tenancies with building, unit label and optional meter id, serial, status and credit. Fetch meters in one batch query, no N+1. Credit reads 0 until M5-02: nothing can change it before then, and that is not a bug.
  - Commit: `feat(meter): let tenants see their tenancies and meters`

## M3: Ledger (weeks 4 to 5)

- [ ] **M3-01 Ledger schema.** `ledger_accounts`, `journal_entries`, `postings` migrations with CHECK constraints (`amount_kobo > 0`), idempotency key unique.
  - Commit: `feat(ledger): add double-entry ledger schema`
- [ ] **M3-02 Account provisioning.** Create TENANT_WALLET at tenant registration (one per user, DESIGN §17.2), LANDLORD_REVENUE on landlord registration, platform accounts seeded by migration. The migration also backfills both for users who registered before M3, idempotently (`INSERT ... SELECT ... ON CONFLICT DO NOTHING`).
  - Commit: `feat(ledger): provision accounts for tenants, landlords and platform`
- [ ] **M3-02a Existing tenant joins a unit.** `POST /tenancies/redeem` with an invite code, for a logged-in tenant. Same wallet, same locking and error rules as registering with a code (DESIGN §8.5).
  - Commit: `feat(property): let existing tenants redeem an invite`
- [ ] **M3-03 Posting service.** `LedgerService.post(JournalEntryCommand)`: validates balance, applies postings, updates account balances with optimistic locking, rejects negative TENANT_WALLET.
  - Done when: `LedgerInvariantTest.everyJournalEntryBalances` and insufficient balance tests pass.
  - Commit: `feat(ledger): post balanced journal entries atomically`
- [ ] **M3-04 Ledger idempotency.** Duplicate `idempotency_key` returns the existing entry (catch unique violation, no check-then-insert).
  - Commit: `feat(ledger): make journal posting idempotent via unique key`
- [ ] **M3-05 Concurrency test.** `ConcurrentPurchaseIT.parallelPurchasesNeverOverdrawWallet` style test at ledger level: 20 threads debit one wallet. Add retry on optimistic lock failure (max 3).
  - Commit: `test(ledger): prove concurrent debits never overdraw a wallet`
- [ ] **M3-06 Wallet read endpoints.** `GET /wallet`, `GET /wallet/transactions`.
  - Commit: `feat(ledger): expose tenant wallet balance and history`
- [ ] **M3-06a Admin bootstrap.** At startup, create the first ADMIN from `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PHONE`, `BOOTSTRAP_ADMIN_PASSWORD` when no admin exists (DESIGN §17.3). Idempotent. Fail startup if only some are set. Never log the password. Logged for now, audited from M7-04.
  - Commit: `feat(identity): bootstrap the first admin from the environment`
- [ ] **M3-07 Trial balance.** `GET /admin/ledger/trial-balance` and scheduled imbalance check emitting metric.
  - Commit: `feat(ledger): add trial balance and imbalance monitor`
- [ ] **M3-08 ADR-0003 and ADR-0007.**
  - Commit: `docs(adr): record ledger and idempotency decisions`

## M4: Payments (weeks 5 to 6)

- [ ] **M4-01 Payment intent model.** Migrations for `payment_intents`, `payment_events`, `webhook_receipts`. State machine with guarded transitions and tests.
  - Commit: `feat(payment): add payment intent state machine`
- [ ] **M4-02 PaymentGateway port and Paystack adapter.** Initialize and verify calls with timeouts, mapped errors. WireMock tests for success, 4xx, 5xx, timeout.
  - Commit: `feat(payment): add paystack gateway adapter`
- [ ] **M4-03 Top-up endpoint.** `POST /wallet/top-ups` with `Idempotency-Key` header; intent committed before gateway call; replay returns same intent; same key with different amount gives 422.
  - Commit: `feat(payment): create wallet top-up intents idempotently`
- [ ] **M4-04 Webhook receiver.** Raw body HMAC-SHA512 verification, receipt dedupe, verify transaction, mark PAID and post top-up journal entry in one transaction.
  - Done when: `duplicateWebhookCreditsWalletOnce` and `invalidSignatureIsRejected` pass.
  - Commit: `feat(payment): confirm top-ups from signed paystack webhooks`
- [ ] **M4-05 Reconciliation job.** Every 5 minutes verify stale PAYMENT_PENDING intents; expire after 60 minutes. ShedLock (or Postgres advisory lock) so only one instance runs it.
  - Commit: `feat(payment): reconcile pending top-ups on a schedule`
- [ ] **M4-06 Check-status fallback.** `GET /wallet/top-ups/{id}` verifies with gateway if still pending.
  - Commit: `feat(payment): verify pending top-up on status check`
- [ ] **M4-07 Payment logging.** Every gateway call written to `payment_events` with correlation ID.
  - Commit: `feat(payment): record every gateway interaction`

## M5: Vending and outbox (week 7)

- [ ] **M5-01 Outbox.** `outbox_events` table with `correlation_id NOT NULL`, `OutboxWriter` used inside business transactions (copies the ID from the MDC, generates one when there is none), relay polling with `FOR UPDATE SKIP LOCKED`, publishes to `energy.events.v1` with an `X-Correlation-Id` header, marks published. Consumers restore the header into the MDC.
  - Done when: IT proves event is not published if the business transaction rolls back.
  - Commit: `feat(outbox): add transactional outbox with kafka relay`
- [ ] **M5-02 Meter credit ledger.** `meter_credit_entries` append-only, `MeterCreditService.apply(delta)` with optimistic locking on meter.
  - Commit: `feat(vending): add append-only meter credit ledger`
- [ ] **M5-03 Buy energy.** `POST /meters/{id}/purchases` per DESIGN §8.2 in one transaction, idempotent, occupant only, 422 when wallet short or meter SUSPENDED/DECOMMISSIONED.
  - Commit: `feat(vending): let tenants buy energy from their wallet`
- [ ] **M5-04 MeterGateway and simulator.** Interface (`pushCredit`, `setRelay`), `SimulatedMeterGateway` storing device state in memory, enabled on `local` and `test`.
  - Commit: `feat(gateway): add meter gateway port with simulator`
- [ ] **M5-05 Credit push consumer.** Consumes `EnergyPurchased`, calls gateway, closes relay if meter was CUTOFF, retries with backoff, marks purchase CREDIT_PUSHED.
  - Commit: `feat(vending): push purchased credit to meters asynchronously`
- [ ] **M5-06 ADR-0004 and ADR-0009.**
  - Commit: `docs(adr): record outbox and meter gateway decisions`

## M6: Telemetry (weeks 8 to 9)

- [ ] **M6-01 Readings schema.** `meter_readings` partitioned by month, unique (`meter_id`, `read_at`), migration creates next 3 partitions; scheduled job creates future ones.
  - Commit: `feat(telemetry): add partitioned meter readings table`
- [ ] **M6-02 Reading simulator producer.** Scheduled producer in `local` profile publishing realistic readings (load profile by time of day) to `meter.readings.v1` keyed by meter id. Respects relay state.
  - Commit: `feat(gateway): simulate meter readings onto kafka`
- [ ] **M6-03 Ingestion consumer.** Idempotent insert, out-of-order handling, DLT after 3 retries.
  - Done when: `duplicateReadingIsIgnored` passes.
  - Commit: `feat(telemetry): ingest meter readings idempotently`
- [ ] **M6-04 Consumption deduction.** First reading sets the baseline and deducts nothing (`last_reading_at IS NULL`, DESIGN §8.3). After that: delta from last register, CONSUMPTION credit entry, threshold evaluation, LOW_CREDIT transition and event.
  - Done when: `TelemetryIT.firstReadingSetsBaselineWithoutDeduction` passes.
  - Commit: `feat(telemetry): deduct consumption from meter credit`
- [ ] **M6-05 Cloud cutoff and restore.** At zero: CUTOFF state, `setRelay(OPEN)`, event. Purchase restores (wired to M5-05).
  - Done when: `CutoffIT.meterIsCutOffAtZeroAndRestoredAfterPurchase` passes.
  - Commit: `feat(telemetry): cut off supply at zero credit and restore on purchase`
- [ ] **M6-06 Readings endpoint.** `GET /meters/{id}/readings?from&to` with max range 31 days.
  - Commit: `feat(telemetry): expose meter readings history`
- [ ] **M6-07 ADR-0006.**
  - Commit: `docs(adr): record two-layer credit enforcement`

## M7: Alerts and audit (week 10)

- [ ] **M7-01 Alerts.** `alerts` table, consumer creating alerts from events, list/acknowledge/resolve endpoints.
  - Commit: `feat(alerting): raise and manage meter alerts`
- [ ] **M7-02 Tamper rules.** Register decrease, consumption while CUTOFF, reading gap > 6 hours. TAMPER_SUSPECTED transition.
  - Done when: `registerDecreaseRaisesTamperAlert` passes.
  - Commit: `feat(alerting): detect meter tampering patterns`
- [ ] **M7-03 Notifications port.** `NotificationSender` interface with logging implementation (email/SMS adapters left as extension points).
  - Commit: `feat(alerting): notify tenants and landlords of alerts`
- [ ] **M7-04 Hash-chained audit log.** `audit_log` table, `AuditService.record`, serialized writes (advisory lock), applied to money, tariff, meter state and tenancy actions, and to admin bootstrap (M3-06a).
  - Commit: `feat(audit): add hash-chained append-only audit log`
- [ ] **M7-04a Withdraw a scheduled tariff.** `withdrawn_at` per DESIGN §6.2: one conditional UPDATE, 409 once the version has started, lookups ignore withdrawn rows, partial unique index, audited.
  - Commit: `feat(tariff): let landlords withdraw a scheduled version`
- [ ] **M7-05 Audit verification.** `GET /admin/audit/verify`; `AuditChainTest.tamperedRowBreaksVerification`.
  - Commit: `feat(audit): verify audit chain integrity`
- [ ] **M7-06 ADR-0008.**
  - Commit: `docs(adr): record hash-chained audit log`
- [ ] **M7-07 Meter replacement.** `POST /meters/{id}/replace` per DESIGN §17.1: final or last known reading, decommission, new row, credit moved with two ADJUSTMENT entries in one transaction, audited. New migration: `assigned_at`, `decommissioned_at`, partial unique `serial_number` and (`gateway_type`, `external_device_id`). METER_REPLACED_UNBILLED_GAP alert records a dead meter's gap in `details`. First to drop if time runs short: if dropped, move it back to DESIGN §16 as an open item.
  - Commit: `feat(meter): replace a meter and carry over its credit`

## M8: Hardening (week 11)

- [ ] **M8-01 Rate limiting.** Bucket4j + Redis, limits from DESIGN §11, 429 with `Retry-After`.
  - Commit: `feat(security): add redis-backed rate limiting`
- [ ] **M8-02 Tariff caching.** Redis cache for active tariff version, evicted on new version.
  - Commit: `perf(tariff): cache active tariff versions in redis`
- [ ] **M8-03 Reports.** Consumption and revenue per building per month (SQL aggregates, no N+1).
  - Commit: `feat(reporting): add building consumption and revenue reports`
- [ ] **M8-04 Metrics and health.** Custom Micrometer metrics from DESIGN §12, readiness/liveness groups.
  - Commit: `feat(observability): add business metrics and health groups`
- [ ] **M8-05 OpenAPI polish.** Security scheme, examples, problem detail schemas, tags.
  - Commit: `docs(api): document endpoints and error responses in openapi`
- [ ] **M8-06 Coverage gate to 75%.** Fill gaps found by JaCoCo (tests, not exclusions).
  - Commit: `test: raise coverage gate to 75 percent`
- [ ] **M8-07 Change password.** `POST /me/password` with `currentPassword` and `newPassword`. Revokes every refresh token family, then returns a fresh token pair so the caller stays signed in. Wrong current password is 422 INVALID_CURRENT_PASSWORD.
  - Commit: `feat(identity): let users change their password`

## M9: Ship (week 12)

- [ ] **M9-01 Dockerfile.** Multi-stage, layered jar, non-root, healthcheck. API and simulator added to compose.
  - Commit: `build: add production dockerfile`
- [ ] **M9-02 Seed data.** `local` profile seeder: landlord, building, 3 units, 3 tenants, 3 meters, tariff, demo credentials in README.
  - Commit: `chore: seed demo data for local runs`
- [ ] **M9-03 README.** Fill in `README.md` (badges, problem, architecture diagram, run steps, API overview, decisions, testing).
  - Commit: `docs: write project readme`
- [ ] **M9-04 Deploy.** Prod profile, Nginx config and systemd/compose files in `deploy/`, deploy to Oracle VM at `meterpay.theodomart.com`, link in README.
  - Commit: `ops: add deployment configuration for oracle vm`
- [ ] **M9-05 (Optional) Tuya adapter.** `TuyaMeterGateway` behind `tuya` profile using Tuya OpenAPI, contract-tested with WireMock.
  - Commit: `feat(gateway): add tuya smart meter adapter`

---

## Branch and commit rules

- One task, one commit on `main`. CI runs on every push.
- Conventional Commits. The subject line says what changed for the system, not "update files".
- Never commit a broken build. `./mvnw verify` locally before pushing.
- Never edit an applied migration. Add a new `V{n}__*.sql`.
- If a task turns out bigger than one commit, split it here first (M3-03a, M3-03b), then build.
