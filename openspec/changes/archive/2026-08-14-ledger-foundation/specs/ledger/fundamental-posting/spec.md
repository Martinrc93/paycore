## Purpose

This capability provides PayCore with an immutable, auditable, and atomic double-entry posting boundary that future accounts, transfers, and payments can use as their authoritative financial record.

## ADDED Requirements

### Requirement: Monetary values are explicit and valid

The system SHALL represent every ledger amount with an explicit currency and a decimal value. The amount SHALL be strictly greater than zero, SHALL respect the configured scale and precision policy for its currency, and SHALL NOT use binary floating-point representation.

#### Scenario: Accept a valid monetary amount
- **WHEN** a posting contains a positive representable decimal amount and an explicit supported currency
- **THEN** the system accepts the amount for transaction validation

#### Scenario: Reject an invalid monetary amount
- **WHEN** a posting contains a zero, negative, or non-representable amount
- **THEN** the system rejects the posting before confirmation and persists no ledger transaction

### Requirement: Transactions contain balanced lines

The system SHALL confirm a financial transaction only when it contains at least one debit line and one credit line, every line references a ledger account, all lines use the same currency, and the sum of debit amounts equals the sum of credit amounts.

#### Scenario: Confirm a balanced transaction
- **WHEN** a transaction has valid open-account references, at least one debit, at least one credit, one currency, and equal debit and credit totals
- **THEN** the system confirms the transaction and its complete set of lines

#### Scenario: Reject an unbalanced transaction
- **WHEN** debit and credit totals differ or one direction has no line
- **THEN** the system rejects the transaction and persists none of its lines

#### Scenario: Reject mixed currencies
- **WHEN** lines in one transaction use more than one currency
- **THEN** the system rejects the transaction before confirmation

### Requirement: Ledger account lifecycle controls posting

The system SHALL provide ledger accounts with one of the accounting types `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, or `EXPENSE`. An account SHALL have one of the states `OPEN`, `BLOCKED`, or `CLOSED`, and only `OPEN` accounts SHALL accept new posting lines.

#### Scenario: Post to open accounts
- **WHEN** every line references an account in `OPEN` state
- **THEN** the system allows the account references to participate in transaction validation

#### Scenario: Reject posting to unavailable accounts
- **WHEN** any line references an account in `BLOCKED` or `CLOSED` state
- **THEN** the system rejects the transaction without changing the account or ledger history

### Requirement: Posting is atomic

The system SHALL persist a confirmed transaction and all of its lines as one atomic operation. If transaction or line persistence fails, the system SHALL commit neither the transaction nor any of its lines.

#### Scenario: Commit a complete posting
- **WHEN** validation succeeds and persistence completes successfully
- **THEN** the transaction and every line are committed and queryable together

#### Scenario: Roll back a failed posting
- **WHEN** persistence fails after the transaction or any line has been prepared
- **THEN** the system rolls back the complete posting and leaves no partial financial movement

### Requirement: Posting retries are idempotent

The system SHALL require an idempotency key for every posting request. A repeated request with the same key and equivalent content SHALL return the original posting result. Reusing a key with different content SHALL be rejected, and concurrent requests with the same key SHALL confirm at most one transaction.

#### Scenario: Return the existing result for an equivalent retry
- **WHEN** a posting is retried with the same idempotency key and equivalent request content
- **THEN** the system returns the original transaction result without creating another transaction

#### Scenario: Reject an idempotency conflict
- **WHEN** a posting reuses an existing idempotency key with different request content
- **THEN** the system rejects the request as an idempotency conflict and preserves the original transaction

#### Scenario: Serialize concurrent retries
- **WHEN** concurrent requests use the same idempotency key and equivalent content
- **THEN** at most one transaction is confirmed and all successful callers observe the same result

### Requirement: Confirmed history is immutable

The system SHALL NOT update or delete a confirmed transaction or any of its lines through the application boundary. Corrections SHALL be represented by a new balanced transaction that references the affected transaction or operation.

#### Scenario: Preserve a confirmed transaction
- **WHEN** a caller attempts to update or delete a confirmed transaction or line
- **THEN** the system rejects the operation and leaves the confirmed history unchanged

#### Scenario: Create an exact compensation
- **WHEN** a caller requests reversal of a confirmed transaction
- **THEN** the system creates a new balanced transaction with opposite directions, references the original, and preserves both transactions

#### Scenario: Create a corrective adjustment
- **WHEN** a caller needs to correct only part of a confirmed financial movement
- **THEN** the system creates a new balanced transaction linked to the affected operation without modifying historical lines

### Requirement: Financial movements are traceable

The system SHALL record an immutable transaction identifier, posting instant, business or value date, idempotency key, operation reference, and ordered lines for every confirmed transaction. Instants SHALL be represented and stored in UTC, and business dates SHALL be represented as calendar dates.

#### Scenario: Record traceability data
- **WHEN** a balanced transaction is confirmed
- **THEN** the system stores all required traceability values with its lines

#### Scenario: Return movements in stable order
- **WHEN** a caller queries movements for a ledger account
- **THEN** the system returns results in a deterministic order based on posting instant, transaction identifier, and line sequence

#### Scenario: Exclude sensitive metadata
- **WHEN** a caller supplies descriptions or operation metadata
- **THEN** the system rejects or sanitizes secrets, credentials, and unnecessary personal data before storing them

### Requirement: Ledger access respects module boundaries

The system SHALL expose ledger posting and movement queries through explicit application contracts. Other modules SHALL NOT depend on ledger persistence entities, database tables, or infrastructure adapters as their integration boundary.

#### Scenario: Use the public ledger boundary
- **WHEN** another module requests a financial posting or movement query
- **THEN** it interacts through the published ledger application contract

#### Scenario: Prevent persistence coupling
- **WHEN** code outside the ledger infrastructure attempts to use ledger persistence internals as an integration path
- **THEN** architecture verification rejects the dependency
