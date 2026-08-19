## MODIFIED Requirements

### Requirement: Monetary values are explicit and valid
The system SHALL represent every ledger amount with an explicit currency and a decimal value. The amount SHALL be strictly greater than zero, SHALL respect the configured scale and precision policy for its currency, and SHALL NOT use binary floating-point representation. Every ledger account SHALL also have one explicit currency, and a posting line SHALL use the same currency as its referenced account.

#### Scenario: Accept a valid monetary amount
- **WHEN** a posting contains a positive representable decimal amount and an explicit supported currency matching its account
- **THEN** the system accepts the amount for transaction validation

#### Scenario: Reject an invalid monetary amount
- **WHEN** a posting contains a zero, negative, or non-representable amount
- **THEN** the system rejects the posting before confirmation and persists no ledger transaction

#### Scenario: Reject a line with an account currency mismatch
- **WHEN** a posting line currency differs from the referenced ledger account currency
- **THEN** the system rejects the posting before confirmation and persists no ledger transaction

### Requirement: Transactions contain balanced lines
The system SHALL confirm a financial transaction only when it contains at least one debit line and one credit line, every line references a ledger account, every line currency matches its account currency, all lines use the same currency, and the sum of debit amounts equals the sum of credit amounts.

#### Scenario: Confirm a balanced transaction
- **WHEN** a transaction has valid open-account references, at least one debit, at least one credit, one currency, matching account currencies, and equal debit and credit totals
- **THEN** the system confirms the transaction and its complete set of lines

#### Scenario: Reject an unbalanced transaction
- **WHEN** debit and credit totals differ or one direction has no line
- **THEN** the system rejects the transaction and persists none of its lines

#### Scenario: Reject mixed currencies
- **WHEN** lines in one transaction use more than one currency
- **THEN** the system rejects the transaction before confirmation

#### Scenario: Reject an account-currency mismatch
- **WHEN** any line currency differs from its referenced account currency
- **THEN** the system rejects the transaction before confirmation

### Requirement: Ledger account lifecycle controls posting
The system SHALL provide ledger accounts with one of the accounting types `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, or `EXPENSE`. An account SHALL have one of the states `OPEN`, `BLOCKED`, or `CLOSED`, and only `OPEN` accounts with a consistent balance projection SHALL accept new posting lines. Accounts may additionally enforce a `NON_NEGATIVE` or `ALLOW_NEGATIVE` balance policy. The natural balance SHALL be calculated as debits minus credits for `ASSET` and `EXPENSE` accounts, and as credits minus debits for `LIABILITY`, `EQUITY`, and `REVENUE` accounts.

#### Scenario: Post to open accounts
- **WHEN** every line references an account in `OPEN` state with a consistent balance projection
- **THEN** the system allows the account references to participate in transaction validation

#### Scenario: Reject posting to unavailable accounts
- **WHEN** any line references an account in `BLOCKED` or `CLOSED` state
- **THEN** the system rejects the transaction without changing the account or ledger history

#### Scenario: Reject posting to an inconsistent account
- **WHEN** any line references an account whose projected balance does not reconcile with its confirmed history
- **THEN** the system rejects the transaction without changing the account, projection, or ledger history

#### Scenario: Calculate natural balance for debit-normal accounts
- **WHEN** an `ASSET` or `EXPENSE` account has cumulative debit amount `D` and cumulative credit amount `C`
- **THEN** its natural balance is `D - C`

#### Scenario: Calculate natural balance for credit-normal accounts
- **WHEN** a `LIABILITY`, `EQUITY`, or `REVENUE` account has cumulative debit amount `D` and cumulative credit amount `C`
- **THEN** its natural balance is `C - D`

### Requirement: Posting is atomic
The system SHALL persist a confirmed transaction, all of its lines, and the corresponding balance-projection updates as one atomic operation. If transaction, line, or projection persistence fails, the system SHALL commit none of those effects.

#### Scenario: Commit a complete posting
- **WHEN** validation succeeds and transaction, line, and projection persistence completes successfully
- **THEN** the transaction, every line, and the resulting projections are committed and queryable together

#### Scenario: Roll back a failed posting
- **WHEN** persistence fails after the transaction, any line, or any projection update has been prepared
- **THEN** the system rolls back the complete posting and leaves no partial financial movement or projection change

### Requirement: Posting retries are idempotent
The system SHALL require an idempotency key for every posting request. A repeated request with the same key and equivalent content SHALL return the original posting result without applying projection deltas again. Reusing a key with different content SHALL be rejected, and concurrent requests with the same key SHALL confirm at most one transaction.

#### Scenario: Return the existing result for an equivalent retry
- **WHEN** a posting is retried with the same idempotency key and equivalent request content
- **THEN** the system returns the original transaction result without creating another transaction or changing projections again

#### Scenario: Reject an idempotency conflict
- **WHEN** a posting reuses an existing idempotency key with different request content
- **THEN** the system rejects the request as an idempotency conflict and preserves the original transaction and projections

#### Scenario: Serialize concurrent retries
- **WHEN** concurrent requests use the same idempotency key and equivalent content
- **THEN** at most one transaction is confirmed and all successful callers observe the same result and resulting projections

## ADDED Requirements

### Requirement: Non-negative balance policy is enforced under concurrency
The system SHALL support a `NON_NEGATIVE` account balance policy. Before confirming a transaction that affects such an account, the system SHALL lock all affected balance projections in deterministic ascending account-identifier order, aggregate the transaction's net deltas, calculate natural balance as debits minus credits for `ASSET`/`EXPENSE` and credits minus debits for `LIABILITY`/`EQUITY`/`REVENUE`, and reject the complete transaction if any resulting natural balance would be negative.

#### Scenario: Accept a posting within a non-negative balance
- **WHEN** a transaction's resulting natural balance remains zero or positive for every affected `NON_NEGATIVE` account
- **THEN** the system confirms the transaction and updates all affected projections atomically

#### Scenario: Reject an overdrawing posting
- **WHEN** a transaction would make any affected `NON_NEGATIVE` account's natural balance negative
- **THEN** the system rejects the complete transaction and changes neither immutable history nor projections

#### Scenario: Serialize concurrent balance checks
- **WHEN** concurrent transactions would consume the same available balance
- **THEN** deterministic projection locking permits no more confirmed spending than the account's non-negative balance allows

### Requirement: Balance projections are reconcilable and rebuildable
The system SHALL maintain cumulative debit and credit projections for ledger accounts, SHALL compare them with confirmed immutable lines during reconciliation, and SHALL provide a rebuild operation that derives projections only from confirmed lines. A mismatch SHALL mark affected accounts inconsistent and block new financial postings until a successful rebuild restores consistency.

#### Scenario: Reconcile a consistent projection
- **WHEN** projected cumulative debits and credits equal the sums of confirmed lines
- **THEN** reconciliation reports the account consistent and leaves posting availability unchanged

#### Scenario: Detect a projection mismatch
- **WHEN** projected cumulative debits or credits differ from confirmed lines
- **THEN** reconciliation marks the affected account inconsistent, raises an operational failure signal, and blocks new postings to it

#### Scenario: Rebuild a mismatched projection
- **WHEN** an inconsistent account is rebuilt from its confirmed immutable lines successfully
- **THEN** the system replaces the projection with the authoritative totals, marks the account consistent, and permits eligible posting again

### Requirement: Historical account currency defaults are deterministic
During the V5 migration, the system SHALL infer an account's currency from its confirmed historical lines when those lines use exactly one currency. If an account has no confirmed historical lines, the system SHALL assign `USD` and `ALLOW_NEGATIVE`. If an account's historical lines contain more than one currency, the migration SHALL fail atomically without applying the schema/data change.

#### Scenario: Infer currency from one historical currency
- **WHEN** an existing account has confirmed lines in exactly one currency
- **THEN** migration assigns that currency and preserves the account's generic balance-policy behavior

#### Scenario: Default an account without history
- **WHEN** an existing account has no confirmed historical lines
- **THEN** migration assigns `USD` and `ALLOW_NEGATIVE`

#### Scenario: Reject mixed historical currencies
- **WHEN** an existing account has confirmed lines in more than one currency
- **THEN** migration fails atomically and leaves the pre-migration schema and data unchanged

### Requirement: Ledger balance contracts remain behind the ledger application boundary
The system SHALL expose account currency, balance-policy validation, balance queries, reconciliation, and rebuild through explicit ledger application contracts. Other modules SHALL NOT depend on ledger persistence entities, balance tables, or infrastructure adapters as their integration boundary.

#### Scenario: Wallet requests a ledger balance through the public boundary
- **WHEN** the wallet module requests an account balance or posting capability
- **THEN** it interacts through a published ledger application contract and receives a transport-neutral result

#### Scenario: Prevent balance persistence coupling
- **WHEN** code outside ledger infrastructure attempts to use ledger account or projection persistence internals as an integration path
- **THEN** architecture verification rejects the dependency
