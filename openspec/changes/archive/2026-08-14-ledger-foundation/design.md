## Context

The repository currently contains the `identity` module and PostgreSQL/Flyway infrastructure, but no ledger implementation. ADR-0003 establishes double-entry accounting, and ADR-0005 defines the concrete posting model: append-only confirmed history, one currency per transaction, idempotent posting, compensating corrections, and a strict boundary between ledger accounts and future Customer operational accounts.

See `proposal.md` and `specs/ledger/fundamental-posting/spec.md` for the motivation and observable requirements.

## Goals / Non-Goals

**Goals:**

- Create a domain-oriented `ledger` module with inward dependencies.
- Validate money, account state, line direction, currency, and balance before persistence.
- Persist confirmed transactions and lines atomically in PostgreSQL.
- Make posting retries safe under duplicate and concurrent requests.
- Preserve immutable history and provide compensating transaction support.
- Expose stable movement queries without leaking persistence models.

**Non-Goals:**

- Customer account ownership or account product lifecycle.
- Optimized balances or balance caches.
- Internal transfers and insufficient-funds rules.
- External payment-provider integration, webhooks, outbox, or reconciliation.
- FX, exchange rates, and cross-currency settlement.

## Decisions

### Module structure

Create the module under `dev.martin.paycore.ledger` with `domain`, `application`, and `infrastructure` packages. Domain models and rules remain framework-free. Application ports define posting and query contracts plus required persistence operations. Infrastructure contains JPA mappings, repositories, adapters, and transport integration if an HTTP boundary is added.

This follows ADR-0002 and avoids making JPA entities or database tables the contract used by future modules.

### Domain model

Use immutable domain values for `Money`, ledger account identity/type/state, transaction identity, line direction, ledger line, and confirmed financial transaction. `Money` normalizes and validates decimal scale according to the chosen currency policy. A transaction factory or validator enforces at least one debit, at least one credit, one currency, positive amounts, open-account references, and equal totals.

The first implementation should keep confirmation as the only persisted transaction state. Draft workflows are excluded so an incomplete transaction cannot appear in confirmed history.

### Persistence model

Use separate tables for ledger accounts, financial transactions, financial transaction lines, and posting idempotency records. Transactions reference accounts through foreign keys. Lines reference their parent transaction and account, carry direction, decimal amount, currency, and deterministic sequence.

The migration must use UTC-compatible timestamp types, business-date `DATE`, non-null constraints, positive-amount checks, uniqueness for identifiers and idempotency keys, and indexes supporting account movement queries. A database transaction surrounds creation of the transaction and all lines.

The application validates the aggregate before persistence, while database constraints provide defense in depth. Append-only behavior is enforced by the adapter contract and restricted mutation paths; database privileges or triggers may be added if the deployment model supports them without obscuring the core design.

### Idempotency and concurrency

Store a request fingerprint with the idempotency key and the resulting transaction identifier. A unique key constraint serializes first ownership of a key. The adapter must distinguish an equivalent retry from a fingerprint conflict and must return the original transaction for equivalent concurrent requests.

The idempotency record and confirmed transaction must be committed atomically. If posting fails, the key must not point to a transaction that does not exist.

### Corrections

Model reversals and adjustments as new posting commands containing a reference to the affected transaction or operation. The correction reuses normal balance and account-state validation. No update or delete use case is exposed for confirmed records.

### Queries

The movement query port accepts an account identifier and a bounded page/window. The adapter orders by posting instant, transaction identifier, and line sequence so pagination and repeated reads are deterministic. Query DTOs are application boundary values, not JPA entities.

### Verification strategy

Use pure domain tests for money, balance, currency, account state, immutability, and compensation. Use ArchUnit tests for module dependencies. Use PostgreSQL/Testcontainers integration tests for Flyway schema, constraints, transaction rollback, idempotency uniqueness, append-only behavior, stable movement ordering, and concurrent posting.

## Risks / Trade-offs

- **[Risk]** Application validation could diverge from database constraints. **Mitigation:** duplicate critical invariants in PostgreSQL and test both domain rejection and database defense in depth.
- **[Risk]** Concurrent equivalent retries could produce duplicate transactions. **Mitigation:** unique idempotency ownership, atomic idempotency/result persistence, and a PostgreSQL integration test with concurrent callers.
- **[Risk]** A balance query over the full ledger may be expensive later. **Mitigation:** exclude optimized balances from this change while preserving the ledger as the authoritative source for a future projection.
- **[Risk]** Append-only enforcement may depend on deployment privileges. **Mitigation:** prohibit mutation in application ports and adapters, add database protections where deployable, and test attempted mutation through the supported boundary.
- **[Risk]** Currency scale policy may be underspecified for future currencies. **Mitigation:** make the policy explicit in the domain and reject unsupported or non-representable values instead of silently rounding.

## Migration Plan

1. Apply the new Flyway migration after existing identity and session migrations.
2. Deploy the ledger module with no existing financial data to backfill.
3. Enable ledger posting only through its application boundary.
4. Verify migration constraints, posting atomicity, idempotency, and movement queries against PostgreSQL.
5. Roll back by deploying the prior application version only before any ledger data is created; once ledger data exists, use a forward corrective migration rather than deleting financial history.

## Open Questions

None. Currency support, idempotency semantics, account states, immutability, and module boundaries are fixed by ADR-0005 and the capability specification.
