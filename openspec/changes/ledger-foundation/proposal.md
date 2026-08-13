## Why

PayCore has adopted double-entry accounting, but it does not yet have an executable ledger boundary for recording financial movements. Establishing that foundation now prevents later accounts, transfers, and payments from introducing mutable balances, partial postings, ambiguous currency behavior, or unauditable corrections.

## What Changes

- Add a foundational `ledger` capability for immutable, balanced financial transactions.
- Represent monetary amounts with an explicit currency and reject invalid amounts or mixed-currency transactions.
- Add ledger accounts with accounting types and posting lifecycle states.
- Add atomic posting of debit and credit lines with domain and persistence-level invariant protection.
- Add idempotent posting requests and reject reuse of an idempotency key with different content.
- Add compensating transactions for reversals and corrections without changing confirmed history.
- Add auditable movement queries with stable ordering and UTC timestamps.
- Explicitly exclude Customer account ownership, optimized balances, internal transfers, external payments, and FX from this change.

## Capabilities

### New Capabilities

- `ledger/fundamental-posting`: Immutable double-entry transactions, ledger accounts, atomic posting, idempotency, compensating corrections, and auditable movement queries.

### Modified Capabilities

None.

## Impact

- Adds a new `ledger` module following the modular-monolith and hexagonal-architecture boundaries.
- Adds domain models and application ports for money, accounts, transactions, lines, posting, and movement queries.
- Adds PostgreSQL persistence and a new Flyway migration for ledger accounts, transactions, lines, and posting idempotency.
- Adds constraints and indexes needed to protect positive amounts, account references, uniqueness, ordering, and immutable confirmed history.
- Adds unit, architecture, and PostgreSQL/Testcontainers integration tests for balance, currency, precision, invalid states, atomicity, rollback, immutability, compensation, idempotency, and concurrency.
- Does not change the existing identity or authentication contracts.
