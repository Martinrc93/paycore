# ADR-0005: Define Ledger Posting Model

## Status

Accepted

## Date

2026-08-13

## Context

ADR-0003 establishes double-entry accounting as the authoritative model for
PayCore financial movements. The next roadmap capability is the foundational
ledger, which must provide an executable and auditable posting boundary before
accounts, internal transfers, and external payments are built.

The high-level decision alone does not define several boundaries that affect
the correctness of every future financial operation:

- how money and currency are represented;
- what makes a transaction postable;
- how retries are handled;
- how confirmed history is corrected;
- which accounts the ledger owns;
- how the ledger is separated from Customer-owned operational accounts; and
- which responsibilities belong to the domain, application, and persistence
  layers.

Without these decisions, later features could introduce mutable balances,
partial postings, ambiguous currency handling, or direct access to ledger
storage.

## Decision

PayCore will implement the ledger as an append-only, double-entry posting
module. The ledger is the authoritative record of financial movements. Future
optimized balances and operational views must be derived from it and must not
replace it as the source of truth.

### Ledger Module Boundary

The `ledger` module owns:

- ledger accounts and their accounting type;
- the lifecycle of ledger accounts;
- financial transactions and their lines;
- posting validation and confirmation;
- posting idempotency;
- compensating transactions; and
- auditable movement queries.

The module does not own Customers, Customer profiles, operational product
accounts, transfers, payment intents, or external payment-provider state.
Those capabilities must use the ledger through an explicit application API or
port and must not access ledger persistence internals directly.

Ledger accounts and Customer operational accounts are deliberately separate
concepts. A later `account` capability may associate an operational account
with a Customer while referring to one or more ledger accounts through a
published application boundary.

### Money and Currency

Monetary values will use `BigDecimal` together with an explicit ISO 4217
currency. Binary floating-point types (`float` and `double`) are prohibited
for monetary values.

The amount carried by a ledger line is strictly positive. The line direction
(`DEBIT` or `CREDIT`) represents the accounting movement; sign is not encoded
by using a negative amount.

The foundational ledger supports one currency per financial transaction. A
transaction containing lines in different currencies is rejected. Currency
conversion, exchange rates, FX gains or losses, and cross-currency settlement
are outside this decision and require a separate design.

Amounts must respect the scale and precision policy defined for the currency.
The domain must reject zero, negative, or otherwise non-representable amounts
before persistence.

### Financial Transactions and Posting

A financial transaction is an immutable group of ledger lines. A transaction
is postable only when it contains at least one debit line and at least one
credit line, all lines use the same currency, and:

```text
SUM(DEBITS) = SUM(CREDITS)
```

The domain validates these invariants before the application requests
persistence. The application confirms the transaction and all of its lines in
one atomic database transaction. A debit without its corresponding credit is
never a valid intermediate or committed state.

The foundational ledger does not persist mutable drafts. Invalid or incomplete
posting requests are rejected before confirmation. If a future workflow needs
drafts, it must define them separately from confirmed ledger history.

Every posting request requires an idempotency key and a deterministic request
identity. A retry with the same key and equivalent content returns the original
posting result. Reusing a key with different content is rejected as an
idempotency conflict. Concurrent requests using the same key must result in at
most one confirmed transaction.

### Immutability and Corrections

Confirmed transactions and their lines are append-only. The system must not
update or delete confirmed financial history through the application API.

An exact reversal creates a new balanced transaction that references the
original transaction. A partial or corrective adjustment also creates a new
balanced transaction with an explicit reference to the affected operation.
Historical records remain available for audit and reconciliation.

### Ledger Account Lifecycle

The foundational ledger owns ledger accounts with these accounting types:

- `ASSET`;
- `LIABILITY`;
- `EQUITY`;
- `REVENUE`; and
- `EXPENSE`.

Ledger accounts have these operational states:

- `OPEN`: accepts postings;
- `BLOCKED`: remains visible for history but rejects new postings; and
- `CLOSED`: permanently rejects new postings.

Only `OPEN` accounts may receive new lines. Closing or blocking an account
does not alter its historical transactions. The exact account catalog,
Customer ownership rules, and balance presentation belong to later capability
designs unless they are required by the foundational posting contract.

### Time, Traceability, and Queries

Point-in-time values use `Instant` and are stored as UTC `TIMESTAMPTZ` values,
consistent with the system timezone specification. A business or value date
uses `LocalDate` and is stored as a `DATE` value.

Each confirmed transaction includes, at minimum:

- an immutable transaction identifier;
- posting instant;
- business or value date;
- idempotency key;
- operation reference; and
- its ordered ledger lines.

Movement queries expose a stable ordering based on posting instant, transaction
identifier, and line sequence. Descriptions and metadata must not contain
secrets, credentials, or unnecessary personal data.

### Architectural Enforcement

The module follows the PayCore hexagonal architecture:

```text
infrastructure  ->  application  ->  domain
```

The domain contains money, account, transaction, line, and posting rules
without dependencies on Spring, JPA, HTTP, PostgreSQL-specific APIs, or
transport DTOs. Application services expose posting and query use cases and
define the required persistence ports. Infrastructure adapters map persistence
entities and transport models without exposing them to the domain.

Persistence must provide defense in depth through foreign keys, uniqueness
constraints, non-null constraints, positive-amount constraints, and indexes.
Database-level protection such as restricted write privileges or triggers may
be used to enforce append-only history, but application validation alone must
not be treated as the only protection for financial invariants.

## Alternatives Considered

### Expand ADR-0003

The existing ADR could be expanded with all posting decisions. This was
rejected because ADR-0003 records the original adoption of double-entry
accounting, while this ADR records the concrete model chosen to implement that
decision. Keeping them separate preserves decision history and makes each ADR
reviewable in its original context.

### Combine Ledger, Accounts, and Transfers

These capabilities could be specified in one financial architecture decision.
This was rejected because ledger posting, Customer account ownership, and
funds movement have different invariants and lifecycle concerns. Combining them
would increase coupling and make future changes harder to isolate.

### Mutable Account Balances as the Primary Model

Operations could update an account balance directly and retain a secondary
movement log. This was rejected because it weakens auditability, makes
reconciliation harder, and allows the balance to diverge from the movement
history. Any optimized balance must remain a derived projection of the ledger.

### Multi-Currency Transactions in the Foundation

The initial ledger could support multiple currencies and exchange-rate lines
from the start. This was rejected as unnecessary complexity for the first
posting boundary. Cross-currency behavior requires explicit FX, rate-source,
rounding, and settlement decisions and will be designed separately.

## Consequences

### Positive

- Financial history is auditable and immutable.
- Every confirmed transaction preserves conservation of value.
- Retries can be handled safely through idempotent posting.
- Corrections preserve the original historical record.
- Future transfers and payments have a stable financial boundary.
- Domain rules can be tested without framework or database dependencies.

### Negative

- Reversals and corrections create additional transactions.
- Posting requires more validation and persistence coordination than a mutable
  balance update.
- Idempotency records and concurrency controls add schema and operational
  complexity.
- Balance queries require a later projection or an aggregate over ledger
  history.
- Multi-currency operations require a separate design before they can be
  supported.

## Invariants

The following invariants are mandatory for the foundational ledger:

```text
SUM(DEBITS) = SUM(CREDITS) for every confirmed transaction
```

- Every confirmed transaction has at least one debit and one credit.
- Every line has a strictly positive amount and an explicit currency.
- All lines in a transaction use the same currency.
- Confirmed transactions and lines are never updated or deleted.
- Corrections are new compensating transactions.
- All lines of one transaction commit atomically or none commit.
- Only `OPEN` ledger accounts accept new postings.
- A posting idempotency key cannot confirm two different transactions.
- No monetary value uses `float` or `double`.

## Scope for the Next OpenSpec Change

The next OpenSpec change should define and verify the foundational posting
capability based on this ADR. It should cover the domain model, application
ports, PostgreSQL schema and migration, atomic persistence, idempotency,
movement queries, and focused unit and integration tests.

The change should not include Customer account ownership, optimized balances,
internal transfers, external payments, or FX.
