## Why

PayCore has immutable double-entry history and verified Customer activation, but it does not yet provide a Customer-owned wallet with explicit currency, available/reserved balances, or a reliable balance projection. This change establishes the wallet and ledger-account foundation before any money-movement capability is introduced.

## What Changes

- Add one USD operational wallet per active Customer, backed by available and reserved `LIABILITY` ledger accounts.
- Extend ledger accounts and posting validation with explicit currency and balance policy, including `NON_NEGATIVE` wallet accounts.
- Maintain synchronous, atomic, reconstructible balance projections from immutable confirmed ledger lines.
- Lock affected balance rows in deterministic account-id order before balance validation and block posting for inconsistent projections.
- Run the coordinated rollout in order: V4 first revalidates Customers by converting `ACTIVE` to `PENDING_VERIFICATION` and revoking sessions; V5 then backfills only Customers that are `ACTIVE` during the controlled interval and enforces that every `ACTIVE` Customer has a complete wallet.
- Add wallet provisioning, lifecycle, own-wallet balance queries, active-Customer backfill, and atomic verified Customer activation with retry-safe convergence; no Customer may become or remain admitted as `ACTIVE` without a complete wallet.
- Add the application boundary that a future confirmed incoming credit will use to activate an `UNFUNDED` wallet; no financial caller is implemented in this change.
- Provide only the authenticated Customer's own wallet query through the wallet application boundary; defer administrative HTTP APIs and all money-movement commands.
- **BREAKING** Require wallet-owned accounts and their postings to obey the account currency and non-negative balance contracts.

## Capabilities

### New Capabilities

- `wallet/accounts-and-balances`: Customer wallet ownership, USD provisioning, lifecycle, balance presentation, projection consistency, and reconciliation.

### Modified Capabilities

- `ledger/fundamental-posting`: Add per-account currency and balance-policy validation, synchronous balance projection, deterministic locking, reconciliation, and blocking of inconsistent accounts while preserving immutable balanced postings.
- `identity/customer-authentication`: Make verified activation atomically provision the Customer wallet and support backfill of wallets for Customers already active at rollout.

## Impact

- Ledger domain, application contracts, persistence schema, posting transaction boundaries, balance projection, and reconciliation operations.
- Identity activation orchestration and Customer-to-wallet integration through module application ports.
- New wallet domain, application, persistence, and authenticated own-wallet query adapters.
- PostgreSQL migration V5 and active-Customer wallet/account backfill; released migrations V1-V4 remain unchanged.
- No transfers, reservations, captures, payments, or administrative HTTP APIs are introduced by this change.
