# Wallet Accounts and Balances Verification

## Status

Implementation, PostgreSQL/Flyway/Testcontainers integration, the complete Maven
suite, and the OpenSpec lifecycle are verified. OpenSpec synchronization and
archive are complete.

## OpenSpec

```text
openspec validate --specs
```

```text
valid: true
items: 1
passed: 1
failed: 0
```

The completion checklist is complete (`42/42`). The archived change is recorded
at `openspec/changes/archive/2026-08-17-wallet-accounts-and-balances`.

## Focused Verification

The non-container focused suite remains green:

```text
./mvnw.cmd "-Dtest=LedgerAccountTest,CreateLedgerAccountServiceTest,ChangeLedgerAccountStatusServiceTest,PostLedgerTransactionServiceTest,CompensateLedgerTransactionServiceTest,LedgerBalanceServicesTest,LedgerReconciliationMetricsTest,WalletTest,ProvisionWalletServiceTest,QueryOwnWalletServiceTest,WalletBalanceReaderAdapterTest,WalletLifecycleServiceTest,WalletFundingActivationServiceTest,WalletProvisioningCustomerActivationAdapterTest,AuthenticateCustomerServiceTest,CustomerOidcAuthenticationSuccessHandlerTest,CustomerStatusFilterTest,AuthenticationConfigurationTest,IdentityArchitectureTest,LedgerArchitectureTest,WalletArchitectureTest,V5MigrationSourceTest" test
Tests run: 105, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Focused PostgreSQL Verification

```text
./mvnw.cmd "-Dtest=WalletPersistenceIntegrationTest,WalletBackfillMigrationTest,LedgerPersistenceIntegrationTest,ProtectedSessionSecurityTest,WalletControllerTest" test
Tests run: 61, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

This run verifies JPA lifecycle/version handling, V5 currency inference and
rollback, projection overflow rollback, active-session wallet invariants,
inconsistent-wallet reads. Docker used the local Npipe socket and
`postgres:17` (PostgreSQL 17.10).

## Architecture Verification

```text
./mvnw.cmd "-Dtest=LedgerArchitectureTest,IdentityArchitectureTest,WalletArchitectureTest" test
IdentityArchitectureTest: 2 tests, 0 failures, 0 errors, 0 skipped
LedgerArchitectureTest: 3 tests, 0 failures, 0 errors, 0 skipped
WalletArchitectureTest: 4 tests, 0 failures, 0 errors, 0 skipped
Total: 9 tests, 0 failures, 0 errors, 0 skipped
BUILD SUCCESS
```

## Full Maven Suite

```text
./mvnw.cmd test
Tests run: 368, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The full run also verified legacy registration/browser fixtures after adding
cleanup for the wallet foreign key and complete wallets for active-session
fixtures. The V4-targeted migration fixture intentionally does not reference
the V5 `wallets` table.

## Evidence

- Wallet JPA save now updates a managed entity, checks current-plus-one domain
  version, and flushes before returning.
- Historical line helpers bind `BigDecimal` as `Types.NUMERIC`.
- V5 backfill failure/constraint fixtures respect V4-before-V5 ordering.
- Projection rollback forces an existing row and observes PostgreSQL `22003`.
- Wallet infrastructure translates only ledger validation into a wallet-owned
  inconsistency exception; retryable `DataAccessException` remains propagating.
- Deferred trigger assertions verify root `PSQLException`, message content, and
  final rolled-back state.

## Closure

OpenSpec synchronization and archive are complete. No Docker or pending gate
remains for this capability.
