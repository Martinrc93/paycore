## 1. Extend the Ledger Account Contract

- [x] 1.1 Add failing domain tests for explicit account currency, `NON_NEGATIVE` and `ALLOW_NEGATIVE` policies, and natural balance calculation by accounting type.
- [x] 1.2 Extend the ledger account command, domain model, persistence mapping, and application contract with preassigned account id, currency, and balance policy.
- [x] 1.3 Add failing tests for posting line/account currency mismatch and reject the mismatch before persistence.
- [x] 1.4 Add architecture coverage proving wallet code uses ledger application contracts rather than ledger persistence internals.

## 2. Add V5 Migration and Wallet Backfill

- [x] 2.1 Add V5 without modifying released V1-V4 migrations.
- [x] 2.2 Add account currency and balance-policy columns, infer one historical currency, default no-history accounts to `USD/ALLOW_NEGATIVE`, and fail migration atomically if historical lines for one account contain multiple currencies.
- [x] 2.3 Create balance projection and consistency data, rebuild it from confirmed immutable lines, and replace account validation to enforce status and currency.
- [x] 2.4 Create wallet schema with unique Customer ownership, available/reserved account references, USD currency, lifecycle status, UTC timestamps, and version.
- [x] 2.5 Apply the ordered rollout: V4 first migrates `ACTIVE` Customers to `PENDING_VERIFICATION` and revokes sessions; V5 then backfills only `ACTIVE` Customers present in the controlled interval, enforces that every `ACTIVE` Customer has a complete wallet, and creates no duplicates or orphan accounts.
- [x] 2.6 Add PostgreSQL/Testcontainers tests for clean migration, currency inference, mixed-currency failure, projection rebuild, active-Customer backfill, idempotent rerun, and rollback.

## 3. Make Posting and Projection Atomic

- [x] 3.1 Add failing tests for account currency mismatch, non-negative rejection, projection rollback, equivalent replay, and concurrent overdraft prevention.
- [x] 3.2 Lock all affected projection rows with SQL `ORDER BY account_id FOR UPDATE` before validating resulting natural balances.
- [x] 3.3 Aggregate account deltas, enforce account consistency and balance policy, then persist immutable header/lines, projection deltas, and idempotency in one transaction.
- [x] 3.4 Preserve replay behavior so an equivalent idempotent retry never reapplies projection deltas.
- [x] 3.5 Add PostgreSQL integration coverage for rollback, lock ordering, and concurrent insufficient-funds behavior.

## 4. Add Reconciliation and Rebuild

- [x] 4.1 Add application contracts and services for querying projected balances, reconciling against confirmed lines, and rebuilding projections.
- [x] 4.2 Reconcile by locking the projection first and aggregating immutable confirmed lines second; mark mismatches `INCONSISTENT` and block affected posting.
- [x] 4.3 Rebuild only from confirmed lines and mark consistency after successful commit without changing ledger history.
- [x] 4.4 Keep metrics and logs bounded and free of Customer/account identifiers, tokens, claims, and amount labels.
- [x] 4.5 Test clean reconciliation, mismatch detection, blocked posting, rebuild, and posting/rebuild races.

## 5. Create the Wallet Module

- [x] 5.1 Add framework-free wallet domain types for ownership, USD currency, `UNFUNDED`, `ACTIVE`, `BLOCKED`, and terminal `CLOSED` lifecycle rules, persisting `pre_block_status` and restoring it on unblock.
- [x] 5.2 Add the wallet provisioning application contract and claim a wallet with preassigned account ids using conflict-safe unique ownership.
- [x] 5.3 Provision available/reserved USD liability accounts and zero projections atomically through ledger contracts.
- [x] 5.4 Ensure concurrent provisioning converges on one complete wallet and never leaves orphan accounts.
- [x] 5.5 Implement the own-wallet application query with wallet balance DTOs that do not expose ledger persistence types.
- [x] 5.6 Add domain, application, persistence, concurrency, lifecycle, zero-balance close, ownership-isolation, and architecture tests.
- [x] 5.7 Protect wallet/account lifecycle state bidirectionally with deferred PostgreSQL validation, preserving type/currency/policy/projection checks and allowing account-first lifecycle transitions.
- [x] 5.8 Add the application boundary for confirmed-incoming-credit activation without implementing or invoking a financial caller.

## 6. Integrate Verified Customer Activation

- [x] 6.1 Preserve the existing verified Customer activation application boundary and route wallet provisioning through an explicit adapter/port.
- [x] 6.2 Coordinate Customer locking, wallet claim, account creation, and status transition in one local transaction; never admit or activate a Customer without a complete wallet.
- [x] 6.3 Roll back all local effects when any provisioning step fails and ensure no local session is accepted on failure.
- [x] 6.4 Verify concurrent verified logins activate once and create one complete wallet with retry-safe rereads.
- [x] 6.5 Verify active-Customer backfill and repeated migration/provisioning do not create duplicates.

## 7. Expose the Own-Wallet Query

- [x] 7.1 Implement authenticated `GET /api/wallet` using the local session Customer identity only.
- [x] 7.2 Return wallet id, lifecycle status, USD currency, available, reserved, and total balances; omit ledger-account ids and other Customer data.
- [x] 7.3 Return sanitized `401`, `403`, not-found/ownership, and inconsistency responses without existence or financial-data leakage.
- [x] 7.4 Add controller/security tests for authentication, own-wallet access, ownership isolation, zero balances, and blocked/inconsistent states.

## 8. Verify the Change

- [x] 8.1 Run focused ledger, migration, wallet, identity activation, and HTTP tests with PostgreSQL/Testcontainers where persistence semantics are involved.
- [x] 8.2 Run architecture tests for identity, wallet, and ledger module boundaries.
- [x] 8.3 Run the complete Maven test suite with Docker available and resolve all regressions.
- [x] 8.4 Validate the OpenSpec change, review every scenario against ADR-0006, and confirm transfers, reservations, captures, payments, and administrative HTTP APIs remain non-goals.
- [x] 8.5 Synchronize/archive this change only after all implementation and verification gates pass.
