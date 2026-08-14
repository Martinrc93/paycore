# ADR-0006: Define Customer Wallet Balance and Reservation Model

## Status

Accepted

## Date

2026-08-14

## Context

PayCore now has Customer identity and authentication capabilities plus an
append-only double-entry ledger. The next roadmap capability must let each
Customer store USD value, observe a reliable balance, transfer value to another
Customer, and authorize payments to stores without allowing double spending.

ADR-0005 deliberately separates Customer-owned operational accounts from
ledger accounts. It also leaves Customer ownership, per-account currency,
optimized balances, and funds-availability rules to a later decision. Those
boundaries must now be defined before wallet, transfer, or payment behavior is
implemented.

The current registration specification marks a Customer `ACTIVE` after
Keycloak provisioning and delivery of required-action email is accepted. The
product model instead requires Customer activation after email verification.
Wallet activation is a separate financial event: a wallet becomes active only
after its first confirmed incoming value.

Merchant payments also require an authorization period because processing can
fail or produce an ambiguous outcome. PayCore therefore needs a reservation
model that prevents the same funds from being spent concurrently while keeping
the ledger authoritative and auditable.

## Decision

PayCore will introduce a domain-oriented `wallet` module between `identity` and
`ledger`. It will own Customer wallet lifecycle, ledger-account associations,
fund reservations, and Customer-facing balance presentation. It will use the
ledger only through published application contracts and will not access ledger
persistence internals.

```text
identity  ->  wallet  ->  ledger
Customer      product     accounting
access        lifecycle   movements
```

### Customer Activation and Wallet Provisioning

`Customer.ACTIVE` will mean that PayCore has observed trusted proof that the
Customer verified the email address. The initial synchronization mechanism will
be the first successful OIDC authentication carrying `email_verified=true` for
the already linked `(issuer, subject)` identity.

The local activation flow will atomically:

1. transition the Customer from pending verification to `ACTIVE`;
2. create exactly one USD wallet for that Customer; and
3. create and associate the wallet's two ledger accounts.

The flow is idempotent. If local provisioning fails, none of those local effects
commits and no PayCore session is created. A later verified login can retry
without duplicating the Customer wallet or ledger accounts.

This synchronization is intentionally simpler than a custom Keycloak event
listener. Local activation therefore occurs on the first verified login, not at
the exact instant the Customer follows the verification link.

### Wallet Model

Every `INDIVIDUAL` and `BUSINESS` Customer has exactly one operational wallet.
The initial wallet product supports USD only. This restriction belongs to the
wallet product; the generic ledger retains its existing currency capabilities.

A wallet contains, at minimum:

- an immutable wallet identifier;
- its unique Customer identifier;
- the fixed `USD` currency;
- an available ledger-account identifier;
- a reserved ledger-account identifier;
- lifecycle status and activation instant;
- creation and update instants; and
- a concurrency version.

Wallet states are:

- `UNFUNDED`: provisioned with zero value and able to receive funds;
- `ACTIVE`: has received its first confirmed incoming value and can operate;
- `BLOCKED`: rejects new Customer-initiated outgoing operations; and
- `CLOSED`: terminal and unavailable for new financial operations.

The first confirmed credit to the wallet activates it. Returning to a zero
balance does not return an active wallet to `UNFUNDED`. A blocked wallet retains
whether it was previously activated so unblocking returns it to `ACTIVE` or
`UNFUNDED` as appropriate.

Operational blocking is separate from ledger-account blocking. Existing
reservations may still be captured, released, or expired, and privileged
compensations and corrections may still post. This preserves obligations and
does not strand reserved funds. A wallet may close only when its total balance
is zero and it has no active reservations. Closing the wallet closes its ledger
accounts while preserving all historical movements.

### Ledger Accounts and Currency

One product wallet is backed by two ledger accounts:

```text
Wallet USD
|-- LIABILITY / AVAILABLE / USD / NON_NEGATIVE
`-- LIABILITY / RESERVED  / USD / NON_NEGATIVE
```

The available account represents value the Customer can spend. The reserved
account represents value still owed to the same Customer but committed to an
authorized merchant payment.

Ledger accounts will gain an explicit currency and balance policy. PostgreSQL
and domain validation will reject a line whose currency differs from its
account currency. Wallet-owned accounts use a `NON_NEGATIVE` policy so no
posting can overdraw available or reserved value, including under concurrent
execution.

### Authoritative and Projected Balances

Confirmed ledger lines remain the authoritative financial record. The ledger
will also maintain a synchronous, reconstructible balance projection containing
cumulative debits and credits for each ledger account.

For the wallet's liability accounts:

```text
account balance  = total credits - total debits
available balance = balance(AVAILABLE)
reserved balance  = balance(RESERVED)
total balance     = available balance + reserved balance
```

Posting and projection updates commit in the same PostgreSQL transaction.
Affected account-balance rows are locked in deterministic account-identifier
order before validating resulting balances. A transaction that would violate a
`NON_NEGATIVE` policy is rejected in full.

The projection is an optimization, not a second source of truth. It must be
rebuildable from immutable ledger lines. Reconciliation compares projected and
authoritative values. A mismatch raises an operational alert and blocks affected
financial operations until the projection is safely rebuilt; ledger history is
never changed to make it match a projection.

### Money-Movement Semantics

User-to-user transfers post immediately and atomically:

```text
DEBIT   sender AVAILABLE
CREDIT  recipient AVAILABLE
```

Equivalent retries return the original result. Concurrent transfers cannot
spend the same available funds twice.

Payments to a `BUSINESS` Customer always use authorization and capture:

```text
Authorization
DEBIT   payer AVAILABLE
CREDIT  payer RESERVED

Capture
DEBIT   payer RESERVED
CREDIT  merchant AVAILABLE

Release or expiry
DEBIT   payer RESERVED
CREDIT  payer AVAILABLE
```

The first version allows one full capture, full cancellation, or full expiry.
Partial and multiple captures are not supported. A reservation is bound to its
payer wallet, merchant wallet, exact USD amount, expiration instant, idempotency
identity, and related ledger transaction identifiers. It cannot be redirected
to another merchant or captured for a different amount.

Reservation states are:

- `RESERVED`;
- `CAPTURED`;
- `RELEASED`; and
- `EXPIRED`.

The default reservation lifetime is configurable and initially 15 minutes.
Capture is permitted only when `now < expiresAt`; expiration applies when
`now >= expiresAt`. Capture, cancellation, and expiration serialize on the
reservation so exactly one terminal transition and one corresponding ledger
posting can commit.

A merchant can capture or cancel only a reservation addressed to its own
`BUSINESS` wallet. Expiration and reconciliation are internal operations.

### Atomicity and Idempotency

Every financial command requires an idempotency key and deterministic request
identity. Repeating equivalent content returns the original result. Reusing a
key with different content is rejected as an idempotency conflict.

The following pairs commit atomically or not at all:

- transfer result and ledger posting;
- reservation creation and authorization posting;
- reservation capture and capture posting;
- reservation release or expiry and its release posting; and
- wallet activation and the first incoming posting when activation is required.

A rollback must never leave a reservation without its posting, a posting
without its reservation transition, or a projected balance without its ledger
transaction.

### Access and Errors

An authenticated Customer may query only its own wallet. Active Customers with
operable wallets may initiate outgoing operations. A blocked wallet cannot
create new outgoing transfers or reservations. Existing obligations and
privileged corrective operations follow the lifecycle rules above.

Application contracts will expose wallet provisioning, own-balance queries,
lifecycle administration, immediate transfers, payment authorization, capture,
cancellation, expiration, and reconciliation without exposing persistence
entities or internal ledger-account identifiers to transport clients.

Expected domain failures include wallet unavailable or blocked, insufficient
available funds, currency mismatch, invalid recipient, reservation unavailable
or finalized, reservation expiry, and idempotency conflict. Transport errors
must not reveal another Customer's wallet existence, balance, email, or ledger
internals.

## Alternatives Considered

### One Mutable Balance on the Wallet

The wallet could store and directly update a balance while keeping ledger
movements as a secondary history. This was rejected because mutable balance
would become a competing source of truth and could diverge from immutable
financial history.

### Reservations Outside the Ledger

Reservations could be stored separately and subtracted from a ledger-derived
balance. This was rejected because available balance would depend on two
financial sources and reservation movements would be less auditable.

### One Ledger Account Per Wallet

A wallet could use one liability account plus a mutable reserved counter. This
was rejected because available and reserved value would not both be derivable
from the ledger. Two internal accounts do not violate the one-wallet product
rule.

### Aggregate the Full Ledger for Every Balance Read

Balances could be calculated by summing all historical lines for every query and
funds check. This was rejected as an operational bottleneck. The synchronous
projection preserves strong consistency while remaining rebuildable.

### Activate Customers Through a Keycloak Event Listener

A custom listener could notify PayCore at the exact email-verification instant.
This was deferred because it adds deployment and delivery complexity. First
verified OIDC login provides trusted evidence with fewer moving parts.

### Apply Reservations to Every Transfer

Peer transfers could also require authorization and capture. This was rejected
because direct user-to-user transfers are expected to settle immediately. The
two-phase model is reserved for merchant payments where processing may fail or
remain ambiguous.

## Consequences

### Positive

- Every Customer has one simple USD wallet product.
- Available, reserved, and total value remain derivable from the ledger.
- Merchant authorizations cannot be double-spent.
- Immediate transfers and merchant payments have distinct, explicit semantics.
- Balance checks remain strongly consistent under concurrency.
- Projection corruption can be detected and repaired without altering history.
- Identity, wallet, and ledger ownership remain separated by application APIs.

### Negative

- Each wallet requires two ledger accounts and creates additional movements.
- Posting now coordinates account-balance locks and a derived projection.
- Reservation expiry requires durable scheduled processing and observability.
- Customer activation behavior must change before wallet provisioning can rely
  on verified email.
- Cross-module local transactions require careful composition and architecture
  tests.
- Partial capture, multiple wallets, multiple currencies, and exact-time email
  activation require future changes.

## Invariants

- Every Customer has at most one wallet, and every active Customer has exactly
  one completely provisioned wallet.
- Every wallet and both of its ledger accounts use USD.
- Available and reserved balances are never negative.
- Total wallet balance equals available plus reserved balance.
- Projected balances are derivable from immutable ledger lines.
- A wallet activates only after its first confirmed incoming value.
- A closed wallet has zero total balance and no active reservations.
- Reserved value remains part of the payer's total balance until capture.
- A reservation has exactly one terminal outcome.
- A reservation can be captured only once, in full, by its intended merchant,
  before expiration.
- Every transfer, authorization, capture, release, and expiry is idempotent and
  atomic with its ledger effects.
- No wallet operation bypasses the ledger application boundary or mutates
  confirmed ledger history.

## Delivery Sequence

This decision will be implemented through separate OpenSpec changes:

1. `activate-verified-customer` changes local Customer activation to the first
   verified OIDC login and establishes the pending-verification state.
2. `wallet-accounts-and-balances` adds wallet provisioning, ledger currency and
   non-negative policy, the synchronous projection, balance queries, lifecycle,
   and reconciliation.
3. `wallet-money-movement` adds immediate peer transfers and merchant payment
   authorization, capture, cancellation, and expiry.

Completed identity and ledger changes should be archived or synchronized before
these deltas are created so their requirements are available as current base
specifications.

## Verification Expectations

Implementation must include pure domain tests, architecture tests, and
PostgreSQL/Testcontainers integration tests covering:

- concurrent idempotent wallet provisioning;
- USD and per-account currency enforcement;
- balance projection updates, rollback, rebuild, and reconciliation;
- concurrent insufficient-funds protection;
- transfer, authorization, capture, cancellation, and expiry idempotency;
- capture versus cancellation versus expiry races;
- wallet blocking, unblocking, activation, and closure;
- atomic reservation and ledger transitions; and
- module boundaries between identity, wallet, and ledger.
