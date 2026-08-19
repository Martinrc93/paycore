# Wallet Balances Runbook

## Purpose and Scope

This runbook covers the current USD wallet and balance foundation:

- one operational wallet per eligible Customer;
- separate available and reserved liability accounts;
- synchronous ledger balance projections;
- reconciliation and rebuild of derived balances; and
- the authenticated own-wallet query at `GET /api/wallet`.

The ledger remains the authoritative financial history. Balance projections are
an optimization and must always be rebuildable from confirmed immutable ledger
lines.

Transfers, reservations, captures, releases, expirations, payment commands,
and administrative HTTP APIs are not part of this capability. Do not invent an
operator endpoint for those operations.

## Preconditions and Rollout

1. Apply migrations with the migration role and verify PostgreSQL connectivity.
   Do not edit or reorder released migrations V1-V4.
2. Stop old PayCore application instances and workers before the coordinated
   V4/V5 rollout. Old binaries must not write the old ledger-account shape.
3. Take an encrypted PostgreSQL backup and record aggregate Customer, wallet,
   account, balance-projection, ledger-line, and session counts without logging
   secrets or token-bearing session attributes.
4. Apply V4 first. Existing `ACTIVE` Customers become
   `PENDING_VERIFICATION` and their local sessions are revoked.
5. Apply V5 in one Flyway transaction. It adds account currency and balance
   policy, creates projections and wallet tables, validates historical currency,
   and backfills only Customers that are `ACTIVE` at the controlled backfill
   point. Wallet validation requires both account projection rows, not only the
   two account references.
6. Confirm that every remaining `ACTIVE` Customer has one complete USD wallet,
   two open USD non-negative liability accounts, and two balance projections.
7. Deploy the application that understands V5, verify migration and balance
   checks, and admit traffic only after no old writer remains.

If V5 fails, treat the migration as failed atomically. Do not manually add
columns, wallets, accounts, or projections to make Flyway appear complete. Use a
forward-compatible fix or restore the matching database and application
version. Never restore an old application binary against the V5 schema.

## Wallet Provisioning

### Normal Provisioning

A wallet is fixed to `USD` and has exactly two associated ledger accounts:

```text
Wallet USD
|-- LIABILITY / AVAILABLE / USD / NON_NEGATIVE
`-- LIABILITY / RESERVED  / USD / NON_NEGATIVE
```

Provisioning creates the wallet in `UNFUNDED` state with zero projections. The
wallet application owns the Customer association and calls ledger application
contracts for account creation and balance initialization. It must not access
ledger repositories, JPA entities, or balance tables directly.

Provisioning serializes requests for the same Customer and returns an existing
wallet when it is already complete. Equivalent concurrent provisioning must
converge on one wallet, two accounts, and two projections. If any account,
projection, or wallet write fails, the local transaction must roll back all
local effects; do not retain or reuse a partially provisioned wallet.

The ledger creation contract initializes each account projection before the
wallet provisioning contract returns. A missing projection for an account with
history is a fail-closed ledger error and must not be recreated as a zero row.

### Verified Activation

The verified OIDC activation path locks the Customer, provisions the wallet,
and changes `PENDING_VERIFICATION` to `ACTIVE` in one local transaction. A
verified login must not create a session when provisioning fails. A later
verified login may retry.

V5 backfill creates `UNFUNDED` wallets for Customers that are `ACTIVE` during
the controlled interval. V5 does not activate pending Customers. A pending
Customer regains access only through verified OIDC activation.

### Lifecycle

- `UNFUNDED`: newly provisioned and able to receive eligible incoming value.
- `ACTIVE`: activated after the first confirmed incoming value; a later zero
  balance does not demote it.
- `BLOCKED`: rejects new Customer-initiated outgoing operations and persists
  `pre_block_status` as `UNFUNDED` or `ACTIVE`; the lifecycle application
  service blocks both associated ledger accounts in the same transaction.
- `CLOSED`: terminal; closure requires zero total balance and no active
  reservations. With no reservation capability yet, reserved balance zero is
  the no-obligation check; closure also closes both associated ledger accounts.

Blocking, unblocking, and closure serialize on the wallet ownership lock. The
service reads the available and reserved projections as one ledger application
operation before evaluating closure and persists the domain's `pre_block_status`
without inferring it from balances.

The current change does not provide lifecycle administration over HTTP or
implement incoming transfers or merchant reservations. Existing obligations
must not be discarded by blocking or closure procedures.

## Balance Semantics

For a wallet's liability accounts, the natural balance is credits minus debits:

```text
available = balance(AVAILABLE)
reserved  = balance(RESERVED)
total     = available + reserved
```

Wallet-owned accounts use `NON_NEGATIVE`. Posting locks affected projection rows
in ascending account-identifier order, aggregates the account deltas, and
rejects the complete posting if a resulting natural balance would be negative.
Posting, immutable ledger lines, idempotency completion, and projection updates
commit together.

The wallet balance reader obtains both account projections through one
transactional ledger query. It rejects a missing projection, inconsistent
projection, non-liability account, or negative natural balance as one sanitized
wallet-unavailable condition.

The own-wallet response contains only these fields:

```text
walletId, status, currency, available, reserved, total
```

The Customer identity comes from the authenticated local principal. The request
does not accept a Customer identifier in a path, query parameter, or body. A
new wallet returns zero USD balances. Ledger-account identifiers and another
Customer's wallet data must never be returned.

## Reconciliation and Rebuild

Reconciliation compares projected cumulative debits and credits with totals
derived from confirmed immutable ledger lines. The projection row is locked
before the historical aggregation. A mismatch marks the account
`INCONSISTENT`, records a bounded operational signal, and blocks affected
financial postings.

Rebuild is an internal ledger application operation. It must:

1. lock the projection row;
2. derive cumulative totals only from confirmed immutable lines;
3. replace the derived projection values in the same transaction; and
4. mark the projection consistent only after the replacement succeeds.

After a rebuild, run reconciliation again. If rebuild fails, keep the account
blocked and preserve both the inconsistent projection state and ledger history.
Never update or delete confirmed transactions or lines to match a projection.

The current code exposes reconciliation and rebuild through application
services, not an administrative HTTP endpoint. Use the owning application
operation or controlled internal tooling when one is provided by deployment;
do not bypass the application boundary with ad hoc writes.

The bounded metrics are:

- `paycore.ledger.balance.reconciliation{outcome=consistent}`;
- `paycore.ledger.balance.reconciliation{outcome=mismatch}`; and
- `paycore.ledger.balance.rebuild`.

Do not add Customer IDs, account IDs, amounts, tokens, claims, credentials, or
raw database errors as metric labels or log fields.

## Failure Handling

### Wallet Query Failures

- Requests rejected before the controller by the Customer status/wallet-access
  checks return sanitized `403` when the Customer is inactive or an `ACTIVE`
  Customer has no complete wallet or has incomplete/inconsistent wallet state.
- A request that reaches the own-wallet application query and cannot find the
  authenticated Customer's own wallet returns sanitized `wallet_unavailable`
  with HTTP `404`.
- A balance or service failure returns sanitized `wallet_unavailable` with HTTP
  `503`.
- Unauthenticated requests continue through the existing `401` response
  without wallet data.
- Both the request status filter and the OIDC success handler require the public
  wallet-access contract to confirm a complete USD wallet for an `ACTIVE`
  Customer. Missing or inconsistent wallet state invalidates the session and
  returns sanitized `403`.

Do not expose wallet existence, status, balances, Customer email, external
identity, or ledger internals in errors.

### Posting or Projection Failures

1. Stop new affected financial work when reconciliation reports a mismatch.
2. Check the sanitized outcome and internal operation reference through the
   application boundary.
3. Confirm that the immutable ledger history was not changed.
4. Rebuild the affected projection through the internal ledger operation.
5. Reconcile again before allowing affected posting to resume.

Database constraint, append-only trigger, currency, status, balance-policy, or
atomicity failures are invariant failures. Do not bypass them with direct SQL
updates.

### Migration or Provisioning Failures

Mixed historical currencies for one ledger account, an incomplete wallet, a
duplicate ownership condition, or a failed backfill invariant must fail the
transaction. Keep traffic disabled, preserve the failure details without
secrets, and use the migration rollback/forward-fix procedure. Never delete
orphan-looking ledger history as a repair shortcut.

## Operational Safety

- Use UTC instants and PostgreSQL `TIMESTAMPTZ`; do not infer activation or
  update order from the JVM default timezone.
- Use encrypted backups and the least-privilege migration/runtime roles.
- Keep credentials, client secrets, cookies, authorization codes, access and
  refresh tokens, claims, and token fragments out of source control, logs,
  metrics, traces, tickets, and dashboards.
- Treat direct database access as controlled investigation only. Financial
  corrections use new compensating ledger transactions, never history edits.
- PostgreSQL/Testcontainers verification is a release gate for persistence
  behavior. A local run without a working Docker daemon is not evidence that
  migration, locking, rollback, or reconciliation integration behavior passed.
