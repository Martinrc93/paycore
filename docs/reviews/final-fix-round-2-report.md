# Final Fix Round 2 Report

## Objective

Close the last Task 9/final-review finding without a commit, without changing
V1-V4, and without widening the wallet capability beyond its OpenSpec scope.

## Implementation

1. V5 wallet-link validation is lifecycle-aware. `UNFUNDED` and `ACTIVE` map to
   `OPEN` linked accounts, `BLOCKED` maps to `BLOCKED`, and `CLOSED` maps to
   `CLOSED`. Type, USD currency, `NON_NEGATIVE` policy, and balance projection
   existence remain mandatory. Operational (`UNFUNDED`/`ACTIVE`) links also
   require both projections to be `CONSISTENT`; `BLOCKED`/`CLOSED` retain their
   existing lifecycle-state semantics. The account-first lifecycle
   implementation can still persist its final wallet state without the link
   trigger rejecting the necessary transition.
2. V5 backfill validation for an existing wallet now requires both linked
   projection rows to be present and `CONSISTENT` before treating the wallet as
   complete.
3. V5's final `ACTIVE`-Customer validation applies the same two-row existence
   and `CONSISTENT` requirement, so an inconsistent projection cannot satisfy
   the active-access invariant.
4. Ledger balances expose `findPairForUpdate` through the application contract.
   The persistence query returns both account metadata and projection data with
   ascending account-id ordering and `FOR UPDATE`. Close reads this pair before
   validating zero total/reserved balances. Posting retains its existing ordered
   projection-row locks, so close and posting serialize on the same rows.
5. Wallet balance reads validate both linked accounts as `OPEN`, `LIABILITY`,
   `USD`, and `NON_NEGATIVE`; projections must exist and be `CONSISTENT`, and
   natural balances must be non-negative. Active-wallet confirmation uses this
   validation and returns empty for invalid account state through its existing
   failure boundary.
6. Pending Customer activation now confirms the provisioned wallet through
   `WalletAccess.confirmCompleteUsdWallet` after `ProvisionWallet.provision`
   and before `activatePendingIfCurrent`. An incomplete or inconsistent wallet
   throws an internal runtime exception inside the transaction, forcing rollback
   of wallet/accounts/projections; the public activation method sanitizes that
   failure as `Optional.empty()` only after the executor returns.
7. `QueryOwnWalletService.confirmCompleteUsdWallet` returns `Optional.empty()`
   only for a valid query with no wallet or an invalid wallet state. Runtime
   infrastructure failures, including retryable `40001/40P01` causes, propagate
   to the external transaction executor. Admission adapters retain their own
   sanitized runtime capture at the HTTP/session boundary.
8. `WalletBalanceReaderAdapter` no longer wraps the ledger pair query in a
   catch-all `IllegalStateException`. `DataAccessException` and SQLState
   retryable causes (`40001`/`40P01`) now propagate intact to
   `PostgresTransactionExecutor`; only validations after a successful pair read
   sanitize incomplete, invalid, incompatible, inconsistent, or negative data.
9. V5 lifecycle status validation is now bidirectional and deferred until
   commit. Constraint triggers observe both wallet and linked-account status
   changes, while type, currency, policy, projection, and operational
   consistency checks remain in the wallet-link validation. Account-first
   `WalletLifecycleService` transitions therefore do not fail on intermediate
   states, while direct SQL changes on either side roll back at commit.
10. Wallet close uses a locked balance read that accepts `BLOCKED` linked
    accounts, preserving zero-total and no-reservation validation before
    closing both accounts.
11. `ActivateAfterConfirmedIncomingCredit` and
    `WalletFundingActivationService` provide the retry-safe
    `activateAfterConfirmedIncomingCredit(customerId, activatedAt)` boundary.
    No posting, transfer, payment, or other financial caller invokes it yet;
    that remains the next money-movement change's responsibility.

## Regression Coverage

- Source lifecycle tests cover block, unblock, close, and the lock-aware close
  read.
- Source reader tests cover closed accounts and incompatible currency/policy.
- Source reader tests are parameterized for SQLState `40001` and `40P01`,
  verify propagation through both `read` and `readForUpdate`, and retain
  invalid-wallet sanitization.
- Source ledger application tests cover the `findPairForUpdate` contract.
- V5 source tests cover consistent projections in the wallet-link trigger, the
  existing-wallet backfill validation, and the final `ACTIVE`-Customer check.
- `WalletBackfillMigrationTest` expects direct `BLOCKED` and `CLOSED` account
  changes to be rejected by the deferred lifecycle trigger, matching the
  persistence integration tests; Docker execution passed in the integration
  debug round below.
- Pending activation tests cover the required provision-confirm-activate order,
  incomplete wallet rejection with rollback, completeness-check exceptions,
  provisioning failure, and serialization retry during confirmation.
- Query-service tests cover valid missing/closed wallet `Optional.empty()` and
  propagation of a retryable infrastructure failure without Spring dependency
  from wallet application code.
- PostgreSQL tests cover block, unblock, close, active-wallet rejection with a
  blocked linked account, active-wallet rejection with a closed linked account,
  rejection of an inconsistent operational projection while preserving block
  behavior, bidirectional direct-SQL lifecycle rejection, close of an empty
  blocked wallet, and a race harness that holds the posting balance-row lock
  while close waits. These tests require Docker.

## Verification Results

```text
./mvnw.cmd "-Dtest=LedgerAccountTest,CreateLedgerAccountServiceTest,ChangeLedgerAccountStatusServiceTest,PostLedgerTransactionServiceTest,CompensateLedgerTransactionServiceTest,LedgerBalanceServicesTest,LedgerReconciliationMetricsTest,WalletTest,ProvisionWalletServiceTest,QueryOwnWalletServiceTest,WalletBalanceReaderAdapterTest,WalletLifecycleServiceTest,WalletFundingActivationServiceTest,WalletProvisioningCustomerActivationAdapterTest,AuthenticateCustomerServiceTest,CustomerOidcAuthenticationSuccessHandlerTest,CustomerStatusFilterTest,AuthenticationConfigurationTest,IdentityArchitectureTest,LedgerArchitectureTest,WalletArchitectureTest,V5MigrationSourceTest" test
```

```text
Tests run: 105, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```text
./mvnw.cmd "-Dtest=WalletPersistenceIntegrationTest,WalletBackfillMigrationTest,LedgerPersistenceIntegrationTest,ProtectedSessionSecurityTest,WalletControllerTest" test
Tests run: 61, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```text
./mvnw.cmd "-Dtest=LedgerArchitectureTest,IdentityArchitectureTest,WalletArchitectureTest" test
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```text
openspec validate --specs
valid: true
items: 1
passed: 1
failed: 0
```

```text
./mvnw.cmd test
Tests run: 368, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

No Docker or verification gate remains pending. No commit was created.

## Integration Debug Round

The focused Docker reproduction initially found 4 failures and 7 errors in
`WalletPersistenceIntegrationTest`, 1 failure and 3 errors in
`WalletBackfillMigrationTest`, 1 failure in `LedgerPersistenceIntegrationTest`,
4 failures in `ProtectedSessionSecurityTest`, and 2 failures in
`WalletControllerTest`. Root causes and exact commands/results are recorded in
`.superpowers/sdd/2026-08-16-wallet-accounts-and-balances/integration-debug-report.md`.

```text
./mvnw.cmd "-Dtest=WalletPersistenceIntegrationTest,WalletBackfillMigrationTest,LedgerPersistenceIntegrationTest,ProtectedSessionSecurityTest,WalletControllerTest" test
Tests run: 61, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```text
./mvnw.cmd test
Tests run: 368, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

OpenSpec strict validation passed with `items=1, passed=1, failed=0`.
Synchronization and archive are complete at
`openspec/changes/archive/2026-08-17-wallet-accounts-and-balances/`.
No commit was created.
