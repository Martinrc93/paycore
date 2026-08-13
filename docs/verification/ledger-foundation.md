# Ledger Foundation Verification

## Scope

Verification evidence for the `ledger-foundation` OpenSpec change and ADR-0005.
The implementation is limited to immutable double-entry posting, ledger
accounts, idempotency, compensating transactions, movement queries, and the
PostgreSQL persistence boundary. Customer account ownership, balances,
transfers, external payments, and FX remain out of scope.

## Results

- Focused ledger tests: **36 tests, 0 failures, 0 errors**.
- Full Maven suite: **229 tests, 0 failures, 0 errors**.
- PostgreSQL/Testcontainers image: `postgres:17`.
- Flyway migrations applied successfully through version `V3`.
- Architecture tests passed for domain/application dependencies and external
  module isolation from ledger infrastructure.
- Independent code review completed; Important findings were corrected and
  the focused and full suites were rerun afterward.

## Verified Behaviors

- Positive `BigDecimal` money with explicit `ARS`, `USD`, `EUR`, or `JPY`.
- Currency scale and persistence precision rejection without silent rounding.
- Balanced debit/credit transactions with one currency and required line
  cardinality.
- `OPEN`, `BLOCKED`, and `CLOSED` ledger account behavior.
- Atomic posting and rollback of idempotency claim, transaction, and lines.
- Equivalent retries return the original transaction, including after an
  account is later blocked.
- Different content under an existing idempotency key is rejected.
- Concurrent equivalent retries confirm one transaction.
- Confirmed transaction and line update/delete attempts are rejected by
  append-only database triggers.
- Empty direct transactions, missing idempotency claims, invalid line
  sequences, and invalid account states are rejected at the database boundary.
- Exact compensations preserve original history and reference the original
  transaction.
- Movement queries use bounded pagination and deterministic ordering.
- UTC instants and business dates are persisted and reloaded correctly.
- Sensitive operation metadata is rejected by the domain boundary.

## Review Notes

The implementation uses a deferred foreign key to insert lines before the
transaction header inside one posting transaction. This allows PostgreSQL to
validate all lines and the header at commit while keeping confirmed history
append-only. Idempotency ownership is claimed before persistence and completed
atomically with the transaction; the transaction idempotency key is also unique
at the database level.
