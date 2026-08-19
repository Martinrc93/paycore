# wallet/accounts-and-balances Specification

## Purpose

This capability gives each Customer one USD operational wallet with reliable available, reserved, and total balances backed by immutable ledger history and explicit lifecycle controls.

## Requirements

### Requirement: A Customer owns at most one USD wallet
The system SHALL associate at most one operational wallet with each `INDIVIDUAL` or `BUSINESS` Customer. Each wallet SHALL have an immutable identifier, the owning Customer identifier, fixed `USD` currency, available and reserved ledger-account references, lifecycle status, activation instant when applicable, UTC creation and update instants, and a concurrency version.

#### Scenario: Provision a new Customer wallet
- **WHEN** an eligible active Customer is provisioned without a wallet
- **THEN** the system creates one USD wallet and exactly two associated USD `LIABILITY` ledger accounts named for available and reserved value, both using `NON_NEGATIVE` balance policy

#### Scenario: Reject a duplicate Customer wallet
- **WHEN** a provisioning request races with or follows an existing wallet for the same Customer
- **THEN** the system converges on the existing completely provisioned wallet and creates no second wallet or orphan ledger account

#### Scenario: Reject an unsupported wallet currency
- **WHEN** a caller requests a wallet currency other than USD
- **THEN** the system rejects the request without creating a wallet or ledger accounts

### Requirement: Wallet lifecycle preserves funded and operational state
The system SHALL support `UNFUNDED`, `ACTIVE`, `BLOCKED`, and terminal `CLOSED` wallet states. A new wallet SHALL be `UNFUNDED`; the first confirmed incoming value SHALL transition it to `ACTIVE`; returning to a zero balance SHALL NOT demote it. When a wallet enters `BLOCKED`, the system SHALL persist its prior operational state in `pre_block_status`, which SHALL be either `UNFUNDED` or `ACTIVE`. A blocked wallet SHALL reject new Customer-initiated outgoing operations, and unblocking SHALL restore the persisted `pre_block_status`. A wallet SHALL close only when its total balance is zero and it has no active reservations.

#### Scenario: Newly provisioned wallet is unfunded
- **WHEN** a wallet is created with zero available and reserved balances
- **THEN** its status is `UNFUNDED` and it can receive eligible incoming value

#### Scenario: First confirmed incoming value activates a wallet
- **WHEN** the wallet receives its first confirmed incoming value
- **THEN** its status becomes `ACTIVE` atomically with that confirmed financial effect

#### Scenario: Zero balance does not demote an active wallet
- **WHEN** an active wallet's available and reserved balances later total zero
- **THEN** the wallet remains `ACTIVE`

#### Scenario: Blocked wallet retains lifecycle history
- **WHEN** an active or unfunded wallet is blocked
- **THEN** new Customer-initiated outgoing operations are rejected and the wallet persists its prior state as `pre_block_status=ACTIVE` or `pre_block_status=UNFUNDED`

#### Scenario: Unblock an active wallet
- **WHEN** a wallet with `pre_block_status=ACTIVE` is unblocked
- **THEN** the wallet returns to `ACTIVE`

#### Scenario: Unblock an unfunded wallet
- **WHEN** a wallet with `pre_block_status=UNFUNDED` is unblocked
- **THEN** the wallet returns to `UNFUNDED`

#### Scenario: Reject closing a wallet with value or reservations
- **WHEN** a caller attempts to close a wallet with a non-zero total balance or an active reservation
- **THEN** the system rejects closure and preserves the wallet and account states

#### Scenario: Close an empty wallet
- **WHEN** a wallet has zero total balance and no active reservations
- **THEN** the system may transition it to terminal `CLOSED`, close its associated ledger accounts, and preserve all historical movements

### Requirement: Confirmed incoming credit exposes an activation boundary
The wallet application SHALL expose `activateAfterConfirmedIncomingCredit(customerId, activatedAt)` for a future money-movement caller. The operation SHALL lock the Customer wallet and execute in the local transaction: an `UNFUNDED` wallet SHALL become `ACTIVE` with the supplied UTC activation instant, while an already `ACTIVE` wallet SHALL be returned unchanged for an equivalent retry. This capability SHALL not post ledger lines, confirm financial transactions, or provide a financial caller; a later money-movement change SHALL invoke the boundary after confirming the incoming credit in the same transaction.

#### Scenario: Activate after a confirmed incoming credit
- **WHEN** the application boundary is invoked for an `UNFUNDED` wallet after an incoming credit is confirmed
- **THEN** the wallet is persisted as `ACTIVE` atomically within the local transaction, without this change implementing the incoming posting

#### Scenario: Retry incoming-credit activation
- **WHEN** the application boundary is invoked again for an already `ACTIVE` wallet
- **THEN** it returns the existing active wallet without changing its activation instant or version

### Requirement: Wallet balances distinguish available, reserved, and total value
The system SHALL derive Customer-facing balances from the two wallet ledger accounts. For each wallet, available balance SHALL equal the natural balance of the available account, reserved balance SHALL equal the natural balance of the reserved account, and total balance SHALL equal available plus reserved. Wallet balances SHALL use `BigDecimal` values with explicit USD currency and SHALL never be negative.

#### Scenario: Return zero balances for a new wallet
- **WHEN** an authenticated Customer queries a newly provisioned wallet
- **THEN** the response contains USD available, reserved, and total balances all equal to zero

#### Scenario: Include reserved value in total
- **WHEN** a wallet has positive available and reserved ledger balances
- **THEN** the returned total equals the sum of available and reserved and reserved value remains part of the Customer's total value

#### Scenario: Reject an inconsistent balance result
- **WHEN** the account projection or authoritative ledger history cannot provide a consistent wallet balance
- **THEN** the system does not return a misleading balance and reports a sanitized operational inconsistency

### Requirement: Balance projections remain atomic and reconstructible
The system SHALL update cumulative debit and credit projections for affected ledger accounts in the same PostgreSQL transaction as confirmed ledger headers and lines. The projection SHALL be rebuildable exclusively from confirmed immutable ledger lines, and no wallet operation SHALL mutate confirmed history to correct a projection.

#### Scenario: Commit wallet-account posting and projection together
- **WHEN** a valid posting affects one or both accounts of a wallet
- **THEN** the confirmed ledger movement and all corresponding projection changes commit together or neither commits

#### Scenario: Roll back projection on posting failure
- **WHEN** a posting or any projection update fails
- **THEN** no partial ledger movement or projection delta remains

#### Scenario: Rebuild projection from history
- **WHEN** an authorized internal rebuild is run for an affected account
- **THEN** the system derives cumulative totals only from confirmed immutable lines and leaves every historical line unchanged

### Requirement: Concurrent balance validation is deterministic
The system SHALL lock all affected balance projection rows in ascending ledger-account identifier order before validating the resulting natural balances. Wallet-owned available and reserved accounts SHALL use `NON_NEGATIVE` policy, and concurrent operations SHALL be serialized so no confirmed operation can overdraw either account.

#### Scenario: Accept a non-overdrawing operation
- **WHEN** an operation leaves every affected wallet account's natural balance zero or positive
- **THEN** the operation confirms and its projection updates commit atomically

#### Scenario: Reject an overdrawing operation
- **WHEN** an operation would make an available or reserved wallet account's natural balance negative
- **THEN** the system rejects the complete operation without changing ledger history or projections

#### Scenario: Avoid lock-order deadlock
- **WHEN** concurrent operations affect overlapping sets of wallet accounts in different request orders
- **THEN** the system acquires projection locks in the same ascending account-id order for every operation

### Requirement: Reconciliation controls inconsistent wallet balances
The system SHALL compare every wallet account's projected cumulative debits and credits with totals derived from confirmed immutable lines. A mismatch SHALL mark the affected account inconsistent, emit a bounded operational signal without Customer identifiers or amount labels, and block affected financial operations until a successful rebuild restores consistency.

#### Scenario: Report a consistent wallet projection
- **WHEN** both wallet account projections equal their confirmed-line totals
- **THEN** reconciliation reports the wallet consistent and leaves eligible operations available

#### Scenario: Block an inconsistent wallet account
- **WHEN** reconciliation finds a mismatch for the available or reserved account
- **THEN** the system marks that account inconsistent, reports an operational alert, and rejects affected financial operations with a sanitized error

#### Scenario: Restore operations after rebuild
- **WHEN** a successful rebuild restores the projected totals for an inconsistent wallet account
- **THEN** the system marks the account consistent and permits eligible operations again

### Requirement: Authenticated Customers can query only their own wallet
The system SHALL expose an authenticated own-wallet query that returns only the caller's wallet identifier, lifecycle status, USD currency, available balance, reserved balance, and total balance. The query SHALL not expose ledger-account identifiers or another Customer's wallet data, and administrative wallet, reconciliation, and rebuild HTTP APIs SHALL not be part of this capability.

#### Scenario: Query an own wallet
- **WHEN** an authenticated active Customer requests the own-wallet query
- **THEN** the system returns that Customer's wallet and USD balances without ledger persistence details

#### Scenario: Reject an unauthenticated wallet query
- **WHEN** a browser requests the own-wallet query without a valid local Customer session
- **THEN** the system returns HTTP 401 and no wallet data

#### Scenario: Prevent wallet ownership disclosure
- **WHEN** a caller attempts to query a wallet belonging to another Customer or supplies another Customer identifier
- **THEN** the system denies the request without revealing whether the other wallet exists, its status, or its balances

### Requirement: Wallet and ledger integration respects module boundaries
The wallet module SHALL access ledger posting, account provisioning, balance queries, and reconciliation only through published ledger application contracts. Transport adapters SHALL access wallet behavior through wallet application contracts and SHALL NOT depend on wallet or ledger persistence entities, tables, or infrastructure adapters.

#### Scenario: Provision accounts through the ledger boundary
- **WHEN** wallet provisioning needs its available and reserved accounts
- **THEN** it invokes the ledger application contract and does not construct or persist ledger infrastructure objects directly

#### Scenario: Preserve architectural isolation
- **WHEN** architecture verification checks identity, wallet, and ledger dependencies
- **THEN** dependencies point inward through application contracts and no module bypasses its owning module's public boundary
