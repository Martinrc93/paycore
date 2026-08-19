## Context

The current baseline contains an immutable double-entry ledger with balanced, idempotent postings and verified Customer activation. Ledger accounts do not yet carry product-level currency or balance-policy metadata, and balances are not projected for fast reads. ADR-0006 defines one USD wallet per Customer, two liability accounts, immutable history as the authority, and a later separate money-movement change. See `proposal.md` for the motivation and the delta specs for the observable contracts.

The change crosses identity, wallet, ledger, persistence, and migration boundaries. PostgreSQL is the transaction authority for account ownership, immutable postings, balance projections, and wallet provisioning. Released Flyway migrations V1-V4 must remain unchanged; this change introduces V5.

## Goals / Non-Goals

**Goals:**

- Add explicit ledger-account currency and balance-policy metadata without weakening generic ledger currency support.
- Maintain a synchronous, reconstructible balance projection and protect non-negative wallet accounts under concurrent posting.
- Provision one USD wallet with available/reserved liability accounts, including safe active-Customer backfill.
- Make verified Customer activation and wallet provisioning one local atomic operation with retry-safe concurrency behavior.
- Support wallet lifecycle state and an authenticated own-wallet read without leaking ledger persistence details.
- Expose the incoming-credit activation boundary without adding a financial caller in this change.
- Provide reconciliation and rebuild controls that block affected operations while preserving immutable history.

**Non-Goals:**

- Transfers, reservations, captures, releases, expirations, or payments; these belong to `wallet-money-movement`.
- Invocation of wallet funding activation from a posting or money-movement service; the boundary is defined here and remains unused until that change.
- Administrative HTTP APIs for wallet lifecycle, reconciliation, or rebuild.
- Multi-currency wallet products, partial captures, or exact email-link event synchronization.
- Direct identity-to-ledger or transport-to-persistence integration.

## Decisions

### Keep the wallet as an owning module over two ledger accounts

The wallet module owns Customer association, USD product rules, lifecycle, and Customer-facing views. Provisioning requests two ledger accounts through the ledger application contract: `LIABILITY/AVAILABLE/USD/NON_NEGATIVE` and `LIABILITY/RESERVED/USD/NON_NEGATIVE`. The wallet stores the two account references but never imports ledger persistence entities.

An alternative was to put wallet ownership on ledger accounts or expose a shared repository. That was rejected because it would make accounting infrastructure the owner of product lifecycle and bypass the modular-monolith boundary.

### Add account metadata through the ledger boundary

Ledger accounts gain explicit currency and a balance policy. Generic ledger accounts retain supported currencies and may use `ALLOW_NEGATIVE`; wallet-owned accounts are fixed to USD and `NON_NEGATIVE`. Domain and database validation both enforce line currency equals account currency.

The alternative of treating USD as an implicit wallet convention was rejected because it would allow invalid lines through generic posting paths and make historical reconciliation ambiguous.

During V5 migration, an account with confirmed historical lines receives the one currency inferred from those lines; mixed currencies fail the migration atomically. An account with no confirmed historical lines receives `USD` and `ALLOW_NEGATIVE` as the conservative generic-ledger default. Wallet-created accounts are an explicit exception and receive `USD` and `NON_NEGATIVE` at creation.

### Use a synchronous balance projection as a derived optimization

Each ledger account receives cumulative debit and credit projection values plus consistency state. Posting aggregates net effects, locks all affected projection rows in ascending account-id order, validates natural balances, writes immutable lines and projection deltas in one transaction, and completes idempotency in that same transaction. The projection is never authoritative and is rebuilt only from confirmed lines.

The alternative of summing all lines for every read or balance check was rejected for operational cost. A mutable wallet balance was rejected because it would create a competing financial source of truth.

### Mark inconsistent accounts and block affected posting

Reconciliation locks the projection row before deriving totals from confirmed history. A mismatch marks the account inconsistent and emits bounded operational telemetry without Customer identifiers, account identifiers, amounts, tokens, or other sensitive labels. Posting checks consistency before accepting affected accounts. A successful rebuild replaces only derived totals and restores consistency; it never edits ledger history.

This favors safe unavailability over silently serving or extending a corrupted projection. Automatic repair was rejected because it could conceal corruption and race with operator diagnosis.

### Serialize balance validation by deterministic row order

Every posting affecting projections sorts account identifiers ascending before acquiring row locks. Validation uses aggregated per-account deltas rather than line-by-line intermediate balances. This prevents lock-order deadlocks and ensures concurrent `NON_NEGATIVE` operations cannot both spend the same balance.

Application-level locks and account-order conventions without database row locks were rejected because they do not protect against multiple application instances or direct transaction concurrency.

### Provision wallet and activation in one local transaction

The verified activation port remains the identity boundary. Its implementation obtains the Customer serialization point, claims or finds the unique wallet using a preassigned wallet identifier and conflict-safe ownership constraint, provisions both account records and zero projections through ledger contracts, and transitions the Customer only if the complete wallet is present. Any failure rolls back Customer state, wallet, accounts, and projections; a later verified login retries. Concurrent equivalent callbacks converge on one wallet and one activation.

Active-Customer backfill uses the same unique ownership constraints and is rerunnable. Backfill creates `UNFUNDED` wallets for Customers active at the wallet migration point; it does not alter Customer status. The verified activation path provisions a wallet before accepting a session.

An asynchronous event or distributed saga was rejected for this local foundation because it would permit an active Customer/session without a complete wallet and would require compensating financial state across modules.

### Persist the lifecycle state that preceded blocking

When a wallet transitions to `BLOCKED`, the system persists its prior operational state in `pre_block_status`, which can only be `UNFUNDED` or `ACTIVE`. Unblocking restores exactly that persisted state; blocking an already blocked wallet does not overwrite it, and `CLOSED` remains terminal.

Keeping this marker on the wallet row makes the unblock result durable across restarts and concurrent lifecycle commands. Inferring the prior state from balances was rejected because an active wallet may legitimately have a zero balance.

### Keep own-wallet presentation narrow

The only transport behavior in this change is an authenticated own-wallet query. The caller identity comes from the established local session, and the response contains wallet id, status, USD currency, and available/reserved/total balances. Ledger-account identifiers and persistence objects stay inside application adapters. Administrative operations remain application/internal concerns and have no HTTP surface here.

## Risks / Trade-offs

- **[Risk] V5 encounters historical accounts with mixed line currencies.** -> Inspect historical lines during migration and fail the migration atomically rather than choosing an ambiguous account currency.
- **[Risk] V5 runs while an old application instance is writing the old account shape.** -> Use a coordinated deployment after V4; stop old instances/workers before applying V5 and admit traffic only after schema and backfill checks pass.
- **[Risk] A projection mismatch blocks valid financial operations.** -> Emit bounded operational signals, rebuild from immutable confirmed lines, and verify consistency before unblocking.
- **[Risk] Concurrent activation or backfill creates duplicate accounts.** -> Enforce unique Customer ownership and wallet-account association constraints, use preassigned identifiers, and return the existing complete wallet on equivalent retries.
- **[Risk] Account lock ordering is bypassed by a future posting path.** -> Keep posting and balance validation behind the ledger application service and cover ordering and module boundaries with architecture/integration tests.
- **[Risk] Activation commits but session creation fails afterward.** -> Treat activation as idempotent; a subsequent verified login resolves the existing active Customer and wallet and can establish a new session.
- **[Trade-off] Wallet lifecycle includes reservation-aware close rules before reservations are implemented.** -> Persist the contract and reserve the state checks for the later money-movement capability; no reservation HTTP API is added now.
- **[Trade-off] Wallet supports USD only while the generic ledger remains multi-currency.** -> Enforce the restriction at the wallet boundary and account association, not by narrowing the generic ledger.

## Migration Plan

1. Stop old application instances and workers, back up PostgreSQL, and record Customer, ledger-account, ledger-line, and session counts.
2. Apply V4/revalidation first: convert every existing `ACTIVE` Customer to `PENDING_VERIFICATION`, revoke that Customer's sessions, and preserve all other Customer statuses.
3. Only after V4 completes, apply V5 in one Flyway transaction: add account currency and balance-policy metadata, validate historical currency inference, create projection and wallet tables, replace account-line validation, and rebuild projections from confirmed lines.
4. During the controlled V4-to-V5 interval, V5 backfills only Customers that are `ACTIVE` at the backfill point. It must be rerunnable and must enforce the post-backfill invariant that every `ACTIVE` Customer has one complete USD wallet; no Customer may transition to or be admitted as `ACTIVE` without one.
5. Fail atomically if historical account lines contain mixed currencies, if a projection rebuild fails, or if any wallet/backfill invariant fails. Verify unique ownership and no orphan accounts.
6. Deploy the application that uses the new ledger and wallet contracts, then verify projection consistency, wallet counts, account currencies/policies, Customer statuses, and absence of old writers before admitting traffic.
7. Monitor bounded migration, provisioning, activation, reconciliation, and own-query outcomes without Customer/account identifiers or amount labels.

Rollback is safe before V5 runs. After V5, do not restore old application binaries against the new schema; use a forward-compatible fix or restore the database and matching application version. Do not edit V1-V4 or mutate confirmed ledger history during recovery.
