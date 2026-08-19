# Wallet Accounts And Balances Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver one USD wallet per active Customer, backed by two ledger accounts and consistent, concurrent, reconcilable balances.

**Architecture:** Add a hexagonal `wallet` module. Integrate `identity -> wallet -> ledger` only through application contracts. PostgreSQL keeps posting, projection, and idempotency atomic.

**Tech Stack:** Java 21, Spring Boot 4.1.0, PostgreSQL 17, Flyway, Maven, JUnit 5, Testcontainers, and ArchUnit.

## Global Constraints

- One USD wallet per Customer.
- Internal accounts are `LIABILITY/AVAILABLE` and `LIABILITY/RESERVED`.
- Both wallet accounts use `NON_NEGATIVE`.
- Immutable ledger history remains authoritative.
- Do not modify V1-V4; create V5.
- Money uses `BigDecimal` with explicit currency only.
- Use UTC and injected `Clock`.
- Exclude transfers, reservations, captures, and payments.
- Expose only authenticated own-wallet `GET /api/wallet`.
- Do not create commits unless explicitly requested by the user.

---

### Task 1: Create And Validate The OpenSpec Change

**Files:**
- Create: `openspec/changes/wallet-accounts-and-balances/.openspec.yaml`
- Create: `openspec/changes/wallet-accounts-and-balances/proposal.md`
- Create: `openspec/changes/wallet-accounts-and-balances/design.md`
- Create: `openspec/changes/wallet-accounts-and-balances/tasks.md`
- Create: `openspec/changes/wallet-accounts-and-balances/specs/ledger/fundamental-posting/spec.md`
- Create: `openspec/changes/wallet-accounts-and-balances/specs/identity/customer-authentication/spec.md`
- Create: `openspec/changes/wallet-accounts-and-balances/specs/wallet/accounts-and-balances/spec.md`

**Steps:**
- [x] Describe account currency, balance policy, synchronous projection, deterministic locking, and reconciliation.
- [x] Describe wallet provisioning, lifecycle, own-wallet query, active-Customer backfill, and atomic verified activation.
- [x] Record transfers, reservations, captures, payments, and administrative HTTP APIs as non-goals.
- [x] Validate all artifacts with the repository OpenSpec command.

**Gate:** Production implementation starts only after the OpenSpec artifacts validate.

### Task 2: Extend The Ledger Account Model

**Files:**
- Create: `src/main/java/dev/martin/paycore/ledger/domain/model/LedgerBalancePolicy.java`
- Create: `src/main/java/dev/martin/paycore/ledger/domain/model/LedgerAccountBalance.java`
- Modify: `src/main/java/dev/martin/paycore/ledger/domain/model/LedgerAccount.java`
- Modify: `src/main/java/dev/martin/paycore/ledger/application/account/CreateLedgerAccountCommand.java`
- Modify: `src/main/java/dev/martin/paycore/ledger/application/account/CreateLedgerAccountService.java`
- Modify: `src/main/java/dev/martin/paycore/ledger/infrastructure/persistence/LedgerAccountEntity.java`
- Modify: `src/main/java/dev/martin/paycore/ledger/infrastructure/persistence/LedgerAccountPersistenceAdapter.java`
- Modify: `src/test/java/dev/martin/paycore/ledger/domain/model/LedgerAccountTest.java`

**Interfaces:**
- `CreateLedgerAccountCommand` accepts preassigned `LedgerAccountId`, type, name, `CurrencyCode`, and `LedgerBalancePolicy`.
- `LedgerAccountBalance` calculates natural balance as debits minus credits for asset/expense accounts and credits minus debits for liability/equity/revenue accounts.

**Steps:**
- [x] Add failing tests for explicit currency, policy, and natural balance.
- [x] Run `./mvnw -Dtest=LedgerAccountTest test` and verify failure.
- [x] Implement the minimal immutable domain model and update mappings.
- [x] Run `./mvnw -Dtest=LedgerAccountTest test` and verify pass.

### Task 3: Add V5 And Verify Migration

**Files:**
- Create: `src/main/resources/db/migration/V5__create_wallet_accounts_and_balances.sql`
- Create: `src/test/java/dev/martin/paycore/wallet/infrastructure/persistence/WalletBackfillMigrationTest.java`
- Modify: `src/test/java/dev/martin/paycore/identity/infrastructure/persistence/VerifiedCustomerMigrationTest.java`

**Steps:**
- [x] Add account currency and balance policy without editing V1-V4.
- [x] Fail migration when historical account lines contain multiple currencies.
- [x] Infer one historical currency, or use `USD/ALLOW_NEGATIVE` only for accounts without history.
- [x] Create balance projection rows and rebuild them from immutable lines.
- [x] Create wallet schema with unique Customer ownership, two account references, status, timestamps, and version.
- [x] Replace account validation trigger to enforce account status and currency.
- [x] Backfill one `UNFUNDED` USD wallet and two USD non-negative liability accounts for each active Customer.
- [x] Add Testcontainers coverage for clean migration, inference, mixed-currency failure, projection rebuild, active-Customer backfill, and rollback.
- [x] Run `./mvnw -Dtest=VerifiedCustomerMigrationTest,WalletBackfillMigrationTest test`.

### Task 4: Make Posting And Projection Atomic

**Files:**
- Create: `src/main/java/dev/martin/paycore/ledger/domain/model/InsufficientLedgerBalanceException.java`
- Create: `src/main/java/dev/martin/paycore/ledger/application/port/out/LedgerBalanceStore.java`
- Create: `src/main/java/dev/martin/paycore/ledger/infrastructure/persistence/LedgerBalanceEntity.java`
- Create: `src/main/java/dev/martin/paycore/ledger/infrastructure/persistence/LedgerBalanceJpaRepository.java`
- Create: `src/main/java/dev/martin/paycore/ledger/infrastructure/persistence/LedgerBalancePersistenceAdapter.java`
- Modify: `src/main/java/dev/martin/paycore/ledger/application/posting/PostLedgerTransactionService.java`
- Modify: `src/main/java/dev/martin/paycore/ledger/infrastructure/persistence/LedgerTransactionPersistenceAdapter.java`
- Modify: `src/test/java/dev/martin/paycore/ledger/application/posting/PostLedgerTransactionServiceTest.java`
- Modify: `src/test/java/dev/martin/paycore/ledger/infrastructure/persistence/LedgerPersistenceIntegrationTest.java`

**Steps:**
- [x] Add failing tests for account currency mismatch, non-negative rejection, replay safety, rollback, and concurrent overdraft prevention.
- [x] Lock all affected balance rows with SQL `ORDER BY account_id FOR UPDATE` before validation.
- [x] Aggregate account deltas and validate natural balance under the lock.
- [x] Insert immutable lines and header, update projections, and complete idempotency in one transaction.
- [x] Preserve replay behavior so an equivalent retry never reapplies projection deltas.
- [x] Run focused unit and PostgreSQL integration tests.

### Task 5: Add Reconciliation And Rebuild

**Files:**
- Create: `src/main/java/dev/martin/paycore/ledger/application/balance/LedgerBalanceQuery.java`
- Create: `src/main/java/dev/martin/paycore/ledger/application/balance/QueryLedgerBalancesService.java`
- Create: `src/main/java/dev/martin/paycore/ledger/application/balance/ReconcileLedgerBalancesService.java`
- Create: `src/main/java/dev/martin/paycore/ledger/application/balance/RebuildLedgerBalancesService.java`
- Create: `src/main/java/dev/martin/paycore/ledger/application/balance/LedgerReconciliationResult.java`
- Create: `src/main/java/dev/martin/paycore/ledger/infrastructure/reconciliation/LedgerReconciliationMetrics.java`

**Steps:**
- [x] Reconcile by locking projection first and aggregating immutable lines second.
- [x] Mark mismatches as `INCONSISTENT` and block affected posting.
- [x] Rebuild only from confirmed lines and mark consistency after successful commit.
- [x] Keep ledger history immutable and metrics free of customer/account IDs and amount labels.
- [x] Test clean reconciliation, mismatch, blocked posting, rebuild, and posting/rebuild races.

### Task 6: Create The Wallet Module

**Files:**
- Create domain: `src/main/java/dev/martin/paycore/wallet/domain/{package-info.java,model/Wallet.java,model/WalletId.java,model/WalletStatus.java}`
- Create application: `src/main/java/dev/martin/paycore/wallet/application/{package-info.java,provisioning/ProvisionWallet.java,provisioning/ProvisionWalletCommand.java,provisioning/ProvisionWalletService.java,query/QueryOwnWalletService.java,query/WalletView.java,port/out/WalletStore.java}`
- Create infrastructure: `src/main/java/dev/martin/paycore/wallet/infrastructure/{package-info.java,persistence/WalletEntity.java,persistence/WalletJpaRepository.java,persistence/WalletPersistenceAdapter.java,config/WalletConfiguration.java}`
- Create tests: `src/test/java/dev/martin/paycore/wallet/{WalletArchitectureTest.java,domain/model/WalletTest.java,application/provisioning/ProvisionWalletServiceTest.java,application/query/QueryOwnWalletServiceTest.java,infrastructure/persistence/WalletPersistenceIntegrationTest.java}`

**Steps:**
- [x] Model `UNFUNDED`, `ACTIVE`, `BLOCKED`, and terminal `CLOSED` states.
- [x] Claim a wallet with preassigned account IDs using conflict-safe SQL.
- [x] Provision available/reserved USD liability accounts and their zero projections atomically.
- [x] Ensure concurrent provisioning converges on one wallet and never creates orphan accounts.
- [x] Implement own-wallet balance view without exposing ledger persistence types.
- [x] Test lifecycle, ownership isolation, idempotent provisioning, zero-balance close rules, and architecture boundaries.

### Task 7: Integrate Verified OIDC Activation

**Files:**
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/persistence/WalletProvisioningCustomerActivationAdapter.java`
- Create: `src/test/java/dev/martin/paycore/identity/infrastructure/persistence/WalletProvisioningCustomerActivationAdapterTest.java`
- Modify: `src/main/java/dev/martin/paycore/identity/infrastructure/persistence/CustomerAccessPersistenceAdapter.java`
- Modify: `src/main/java/dev/martin/paycore/identity/infrastructure/security/AuthenticationSecurityConfiguration.java` only if bean wiring requires it.

**Steps:**
- [x] Preserve `CustomerActivationPort.activatePending(CustomerId, Instant)` as the identity boundary.
- [x] Implement Customer lock, wallet provisioning, account creation, and status transition in one transaction.
- [x] Roll back all local effects when any provisioning step fails.
- [x] Verify concurrent verified logins activate once and create one wallet.
- [x] Verify failed provisioning creates no local session.
- [x] Run focused authentication, persistence, and OIDC success-handler tests.

### Task 8: Expose Own Wallet HTTP Query

**Files:**
- Create: `src/main/java/dev/martin/paycore/wallet/infrastructure/web/WalletController.java`
- Create: `src/main/java/dev/martin/paycore/wallet/infrastructure/web/WalletResponse.java`
- Create: `src/main/java/dev/martin/paycore/wallet/infrastructure/web/WalletExceptionHandler.java`
- Create: `src/test/java/dev/martin/paycore/wallet/infrastructure/web/WalletControllerTest.java`

**Steps:**
- [x] Implement authenticated `GET /api/wallet`.
- [x] Resolve Customer ID from `Principal.getName()` only.
- [x] Return wallet ID, status, currency, available, reserved, and total balances.
- [x] Do not return ledger account IDs or another Customer's data.
- [x] Verify `401`, `403`, zero-balance response, isolation, and sanitized inconsistency errors.
- [x] Run `./mvnw -Dtest=WalletControllerTest,ProtectedSessionSecurityTest test`.

### Task 9: Verify Architecture, Documentation, And Completion

**Files:**
- Create: `docs/runbooks/wallet-balances.md`
- Create: `docs/verification/wallet-accounts-and-balances.md`
- Modify: `docs/ROADMAP.md`

**Steps:**
- [x] Document provisioning, balance semantics, reconciliation, rebuild, and operational failure handling.
- [x] Record focused test evidence and exact full-suite results.
- [x] Update the roadmap only after the capability is verified.
- [x] Run `./mvnw -Dtest=LedgerArchitectureTest,IdentityArchitectureTest,WalletArchitectureTest test`.
- [x] Run `./mvnw test` with Docker/Testcontainers available.
- [x] Sync and archive OpenSpec only after all requirements and verification gates pass.

**Verification status:** Complete. Focused verification passed with 105 tests,
focused PostgreSQL verification passed with 61 tests, architecture verification
passed with 9 tests, and the full Maven suite passed with 368 tests. OpenSpec
sync/archive is complete at
`openspec/changes/archive/2026-08-17-wallet-accounts-and-balances/`.
