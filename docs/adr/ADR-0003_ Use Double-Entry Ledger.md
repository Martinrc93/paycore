# ADR-0003: Use Double-Entry Ledger

## Status

Accepted

## Date

2026-08-07

## Context

PayCore must maintain a reliable and auditable record of financial movements.

A simple balance model where operations directly modify an account balance:

```text
balance = balance - amount
```

does not provide enough information to reliably explain how money moved through the system.

Financial operations must provide:

- traceability;
- consistency;
- auditability;
- reversibility;
- reconciliation.

Examples include:

- transfers;
- payments;
- captures;
- refunds;
- adjustments.

## Decision

PayCore will use a **double-entry ledger** as the authoritative record of financial movements.

Every financial transaction must produce balanced ledger entries.

For every transaction:

```text
SUM(DEBITS) = SUM(CREDITS)
```

Example transfer:

```text
Transfer ARS 10,000

Source Account
DEBIT  ARS 10,000

Destination Account
CREDIT ARS 10,000
```

Both entries belong to the same financial transaction.

## Ledger Immutability

Confirmed ledger entries are immutable.

The system must not update or delete confirmed ledger entries.

Incorrect financial movements must be corrected using new compensating entries.

Example:

```text
Original transaction
       ↓
Compensating transaction
```

The historical transaction remains available for audit purposes.

## Transactional Consistency

All ledger entries belonging to the same financial transaction must be persisted atomically.

The system must never allow:

```text
DEBIT  persisted
CREDIT failed
```

A financial transaction must either:

```text
COMMIT completely
```

or:

```text
ROLLBACK completely
```

## Balance

Account balances may eventually be maintained as optimized projections or cached values.

However, the ledger remains the authoritative source for financial movement history.

Any optimized balance representation must remain consistent with ledger transactions.

## Money Representation

Monetary values must not use binary floating-point types such as:

```text
float
double
```

PayCore will use:

```text
BigDecimal
+
explicit Currency
```

for monetary values.

## Alternatives Considered

### Mutable Account Balance Only

Each operation directly modifies an account balance.

This approach is simpler but provides poor auditability and makes reconciliation and correction more difficult.

It was rejected.

### Single Ledger Entry Per Operation

Each operation produces only one movement record.

This approach does not enforce conservation of value between accounts and makes financial inconsistencies harder to detect.

It was rejected.

## Consequences

### Positive

- Complete financial audit trail.
- Strong accounting invariants.
- Easier reconciliation.
- Easier investigation of financial inconsistencies.
- Corrections preserve historical information.
- Supports transfers, payments and refunds consistently.

### Negative

- More records are created.
- Financial operations become more complex.
- Balance calculation requires careful design.
- Reversals must be implemented using compensating entries rather than updates.

## Invariants

The following invariants must always hold:

```text
SUM(DEBITS) = SUM(CREDITS)
```

Confirmed ledger entries are immutable.

Financial transactions are atomic.

Money is represented using BigDecimal and an explicit currency.

Corrections create compensating transactions instead of modifying historical entries.