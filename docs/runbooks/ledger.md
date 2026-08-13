# Ledger Runbook

## Purpose

The ledger is PayCore's authoritative record of financial movements. Confirmed
transactions are immutable, balanced double-entry postings. Account balances
must be derived from ledger history or from a projection that can be rebuilt
from it.

## Posting Rules

- Every posting requires an idempotency key and operation reference.
- Every transaction must contain at least one debit and one credit.
- Debit and credit totals must be equal.
- All lines in a transaction must use the same supported currency.
- Amounts are positive `BigDecimal` values. `ARS`, `USD`, and `EUR` allow two
  decimal places; `JPY` allows zero decimal places.
- Only `OPEN` ledger accounts accept new lines.
- Confirmed transactions and lines must never be updated or deleted.

## Retry and Idempotency

Retry the same posting with the original idempotency key and equivalent request
content. PayCore returns the original transaction instead of creating a new
movement. Reusing a key with different content is an idempotency conflict and
must be investigated as a caller or integration defect.

Concurrent requests with the same key are serialized by PostgreSQL. At most
one transaction is confirmed for that key.

## Corrections

Do not edit or delete a confirmed transaction. Use a compensating transaction:

- exact reversal: creates opposite debit and credit directions and references
  the original transaction;
- corrective adjustment: creates a new balanced transaction for the required
  delta and references the affected transaction.

The original history must remain available for audit and reconciliation.

## Failure Handling

Posting persists the idempotency claim, transaction, and all lines atomically.
If any step fails, the database transaction rolls back and leaves no partial
financial movement or orphaned idempotency result. Retry only after checking
whether the original request committed; use the same idempotency key.

Database constraint or append-only trigger failures indicate an invariant or
mutation violation. Do not bypass them with direct database edits.

## Investigation Queries

Movement queries must use the application query boundary and bounded windows.
Results are ordered by posting instant, transaction identifier, and line
sequence. Direct access to ledger tables is reserved for controlled operations,
reconciliation, and incident investigation.

When investigating a posting, retain only the transaction identifier,
idempotency key reference, operation reference, timestamps, and invariant
failure details. Do not place credentials, tokens, full payment data, or
unnecessary personal information in logs, descriptions, or metadata.

## Operational Checks

1. Confirm PostgreSQL and Flyway are healthy before enabling posting.
2. Confirm the ledger migration is applied at the expected version.
3. Check failed requests by idempotency key and transaction identifier.
4. Reconcile transaction lines by transaction and currency; debit and credit
   totals must match.
5. Correct discrepancies with a new compensating transaction and preserve the
   original records.
