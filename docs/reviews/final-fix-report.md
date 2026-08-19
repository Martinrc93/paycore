# Final Fix Report

## Scope

This report records the global re-review correction for
`wallet-accounts-and-balances`. The change remains uncommitted and keeps V1-V4,
the existing transaction boundaries, the modular architecture, and the
OpenSpec non-goals. No transfers, reservations, captures, payments, or
administrative HTTP APIs were added.

## Findings Addressed

### Important: V5 lifecycle trigger ordering and bidirectional protection

`validate_wallet_account_links` preserves liability, USD,
`NON_NEGATIVE`, projection-row, and operational-consistency constraints. Two
deferred constraint triggers validate the final lifecycle mapping at commit:
`UNFUNDED`/`ACTIVE` require `OPEN`, `BLOCKED` requires `BLOCKED`, and `CLOSED`
requires `CLOSED`. The triggers observe both wallet and linked-account changes,
so account-first lifecycle transitions are accepted while direct SQL changes on
either side are rejected atomically.

### Important: close versus posting

The ledger application contract now exposes `findPairForUpdate`. The SQL pair
query selects both account metadata and projection rows in ascending account-id
order with `FOR UPDATE`. `WalletLifecycleService.close` uses that read before
checking total and reserved balances. Posting continues to lock the same
projection rows in the same deterministic order.

### Important: complete wallet-account validation

Pair results now include account status, currency, and balance policy.
`WalletBalanceReaderAdapter` requires both accounts to be `OPEN`, `LIABILITY`,
`USD`, and `NON_NEGATIVE`, with consistent existing projections and non-negative
natural balances. `confirmCompleteUsdWallet` therefore rejects incomplete
active wallets through the existing sanitized failure path.

### Minor: blocked-wallet close and incoming-credit boundary

Wallet close now uses a locked balance read that accepts `BLOCKED` accounts and
still validates zero total and no active reservations. The wallet application
also exposes the retry-safe `activateAfterConfirmedIncomingCredit` boundary;
no financial posting or money-movement caller is implemented in this change.

## Verification

| Area | Command | Result |
| --- | --- | --- |
| Focused non-container tests | `./mvnw.cmd "-Dtest=LedgerAccountTest,CreateLedgerAccountServiceTest,ChangeLedgerAccountStatusServiceTest,PostLedgerTransactionServiceTest,CompensateLedgerTransactionServiceTest,LedgerBalanceServicesTest,LedgerReconciliationMetricsTest,WalletTest,ProvisionWalletServiceTest,QueryOwnWalletServiceTest,WalletBalanceReaderAdapterTest,WalletLifecycleServiceTest,WalletFundingActivationServiceTest,WalletProvisioningCustomerActivationAdapterTest,AuthenticateCustomerServiceTest,CustomerOidcAuthenticationSuccessHandlerTest,CustomerStatusFilterTest,AuthenticationConfigurationTest,IdentityArchitectureTest,LedgerArchitectureTest,WalletArchitectureTest,V5MigrationSourceTest" test` | 105 tests, 0 failures, 0 errors; `BUILD SUCCESS` |
| Architecture | `./mvnw.cmd --% -Dtest=LedgerArchitectureTest,IdentityArchitectureTest,WalletArchitectureTest test` | 9 tests, 0 failures, 0 errors; `BUILD SUCCESS` |
| OpenSpec | `openspec validate --specs` | valid, 1 passed, 0 failed |
| Focused PostgreSQL tests | `./mvnw.cmd "-Dtest=WalletPersistenceIntegrationTest,WalletBackfillMigrationTest,LedgerPersistenceIntegrationTest,ProtectedSessionSecurityTest,WalletControllerTest" test` | 61 tests, 0 failures, 0 errors; `BUILD SUCCESS` |
| Full suite | `./mvnw.cmd test` | 368 tests, 0 failures, 0 errors; `BUILD SUCCESS` |

The PostgreSQL execution of V5, lifecycle trigger transitions, pair locking,
active-wallet status rejection, and the close/posting race passed with
`postgres:17`.

OpenSpec synchronization and archive are complete at
`openspec/changes/archive/2026-08-17-wallet-accounts-and-balances/`.

`WalletBackfillMigrationTest` now has the same direct-SQL expectation as the
wallet persistence integration tests: changing a linked account to `BLOCKED`
or `CLOSED` while its wallet remains `UNFUNDED` is rejected and leaves the
account `OPEN`.

## Changed Areas

- `src/main/resources/db/migration/V5__create_wallet_accounts_and_balances.sql`
- `src/main/java/dev/martin/paycore/ledger/application/balance/QueryLedgerBalancesService.java`
- `src/main/java/dev/martin/paycore/ledger/application/port/out/LedgerBalanceStore.java`
- `src/main/java/dev/martin/paycore/ledger/infrastructure/persistence/LedgerBalanceJpaRepository.java`
- `src/main/java/dev/martin/paycore/ledger/infrastructure/persistence/LedgerBalancePersistenceAdapter.java`
- `src/main/java/dev/martin/paycore/wallet/application/lifecycle/WalletLifecycleService.java`
- `src/main/java/dev/martin/paycore/wallet/infrastructure/ledger/WalletBalanceReaderAdapter.java`
- Source and PostgreSQL regression tests under `src/test/java/dev/martin/paycore/wallet` and `src/test/java/dev/martin/paycore/ledger/application/balance`
