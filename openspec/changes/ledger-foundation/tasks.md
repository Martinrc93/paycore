## 1. Module and Domain Foundation

- [x] 1.1 Create the `ledger` module package structure and architecture test boundaries for `domain`, `application`, and `infrastructure`.
- [x] 1.2 Implement immutable money and currency values with positive-amount, scale, precision, and non-floating-point validation.
- [x] 1.3 Implement ledger account identity, accounting type, lifecycle state, and valid state transitions.
- [x] 1.4 Implement immutable ledger lines and confirmed financial transactions with debit/credit balance validation, one-currency enforcement, and required line cardinality.
- [x] 1.5 Implement compensating reversal and corrective-adjustment domain behavior without update or delete operations for confirmed records.
- [x] 1.6 Add domain tests for valid money, invalid amounts, precision, balanced and unbalanced transactions, mixed currencies, account states, immutability, reversals, and adjustments.

## 2. Application Boundary

- [x] 2.1 Define posting commands, idempotency identity, operation references, traceability values, and application result types without exposing persistence entities.
- [x] 2.2 Define application ports for posting, ledger account access, idempotency coordination, and bounded movement queries.
- [x] 2.3 Implement the posting use case so domain validation occurs before persistence and confirmed transaction plus lines are committed atomically.
- [x] 2.4 Implement equivalent retry, idempotency conflict, and compensating-transaction application behavior.
- [x] 2.5 Add application tests for successful posting, validation failures, compensation, stable movement query boundaries, and sanitized metadata.

## 3. PostgreSQL Persistence

- [x] 3.1 Add a new Flyway migration for ledger accounts, financial transactions, transaction lines, and posting idempotency records.
- [x] 3.2 Add database constraints for required fields, positive amounts, supported directions/types/states, foreign keys, one transaction currency, uniqueness, and idempotency ownership.
- [x] 3.3 Add indexes for idempotency lookup and deterministic account movement queries using UTC timestamps, transaction identifiers, and line sequences.
- [x] 3.4 Implement persistence entities and mappings separate from domain objects.
- [x] 3.5 Implement the atomic posting adapter, including idempotency fingerprint handling and original-result retrieval.
- [x] 3.6 Implement the movement query adapter with stable ordering and bounded pagination.
- [x] 3.7 Enforce append-only confirmed history through supported adapter paths and database protections available in the deployment model.

## 4. Integration and Concurrency Verification

- [x] 4.1 Add PostgreSQL/Testcontainers tests proving Flyway schema creation and valid balanced posting.
- [x] 4.2 Add integration tests proving rollback leaves no transaction, line, or orphaned idempotency result after persistence failure.
- [x] 4.3 Add integration tests for blocked and closed accounts, database constraints, immutable history, exact reversals, and corrective adjustments.
- [x] 4.4 Add concurrent posting tests proving equivalent retries confirm at most one transaction and observe one result.
- [x] 4.5 Add movement query tests proving stable ordering, pagination boundaries, UTC instants, business dates, and traceability fields.
- [x] 4.6 Run focused ledger tests, architecture tests, and the complete Maven test suite with Docker-backed PostgreSQL available.

## 5. Documentation and Completion Gate

- [x] 5.1 Document the ledger application boundary and operational behavior in a ledger runbook without exposing secrets or sensitive metadata.
- [x] 5.2 Perform security and architecture review for module coupling, mutation paths, idempotency, metadata handling, and database privileges.
- [x] 5.3 Record verification evidence and update the roadmap status only after all requirements and tests are satisfied.
