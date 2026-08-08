## Context

See `proposal.md` for motivation and `specs/identity/customer-registration/spec.md` for the behavior contract.

PayCore currently has no Customer model, identity module implementation, or Flyway migrations. This change establishes the public identity-module boundary required by `authenticate-customer` while preserving ADR-0001 modularity, ADR-0002 hexagonal dependencies, and ADR-0004 UTC handling.

PostgreSQL and Keycloak cannot participate in one atomic transaction. Keycloak 26.5.2 supports user creation, required actions, and time-limited execute-actions email through its Admin API. Registration therefore accepts work durably and processes it asynchronously with a PostgreSQL-coordinated worker.

## Goals / Non-Goals

**Goals:**

- Establish the minimal Customer identity required for later authentication.
- Make valid public responses independent from email existence and Keycloak latency or availability.
- Make concurrent client requests and worker execution deterministic and recoverable.
- Avoid password handling and automatic external identity linking by email.
- Keep Keycloak, scheduling, HTTP, transactions, and persistence outside the domain.

**Non-Goals:**

- Capture individual/business profiles, addresses, tax data, representatives, beneficial owners, KYC, or documents.
- Authenticate Customers or issue sessions.
- Guarantee exactly-once action-email delivery.
- Introduce a message broker or a separately deployed worker service.
- Expose registration progress or reconciliation state publicly.

## Decisions

### Accept registration before contacting Keycloak

For every structurally valid, non-rate-limited request, the HTTP path performs only bounded PostgreSQL work and returns the same generic HTTP 202 response. It never contacts Keycloak. This keeps new and existing emails independent from Keycloak outage status and remote latency.

The transaction reserves the idempotency operation and then either:

- creates one `PROVISIONING` Customer and marks the operation ready for identity creation; or
- marks the operation as duplicate-suppressed when canonical email uniqueness finds an existing Customer.

Both outcomes have the same public response. Duplicate-suppressed operations have no external side effects.

Alternative considered: synchronous provisioning. Rejected because existing emails could return immediately while new emails expose Keycloak latency or HTTP 503, creating an account-enumeration oracle. Alternative considered: an external broker. Deferred because PostgreSQL already provides durable coordination for current scale.

### Define precise canonical inputs

The initial email format is deliberately narrower than all RFC possibilities: ASCII dot-atom local part, one `@`, valid DNS-style domain, no controls or whitespace, local part at most 64 characters, and total length at most 254. Surrounding whitespace is removed before validation; accepted characters are lowercased with locale-independent rules. Quoted and internationalized addresses are rejected until explicitly supported end-to-end by PayCore, PostgreSQL, and Keycloak.

`Idempotency-Key` is opaque and limited to 1-128 bytes. A versioned keyed digest is stored instead of the raw key. The key-ring retains every digest secret for at least the 24-hour idempotency window so rotation cannot invalidate live keys. The request fingerprint covers canonical email and Customer type.

Completed and duplicate-suppressed operation results remain queryable by key for at least 24 hours. After retention expires, the key can be reused; canonical email uniqueness still prevents another Customer. Reconciliation-required operations are retained until resolved.

### Use explicit Customer and operation state machines

Customer states introduced here are:

```text
PROVISIONING -> ACTIVE
PROVISIONING -> PROVISIONING_FAILED
```

`SUSPENDED` and `BLOCKED` are reserved for later status-management behavior but remain non-authenticatable.

Registration operation states are:

```text
PENDING_IDENTITY
      |
      v
IDENTITY_LINKED
      |
      v
COMPLETED

DUPLICATE_SUPPRESSED       (terminal public success)
RECONCILIATION_REQUIRED    (terminal until operator action)
```

The initial request atomically inserts the operation and its new Customer, or records duplicate suppression. After Keycloak user creation or safe recovery, one local transaction inserts the unique external identity link and moves the operation to `IDENTITY_LINKED`. After Keycloak accepts execute-actions email, one local transaction activates the Customer and completes the operation. These paired writes cannot be observed halfway.

An external call whose successful response is lost is not considered completed. Recovery proves whether the side effect occurred before advancing the persisted state.

### Coordinate workers with leases and fencing

An in-process scheduled worker polls due non-terminal operations. Claiming uses a short PostgreSQL transaction with row locking/skip-locked semantics and atomically writes:

- a random claim token;
- an incremented fencing version;
- `lease_until` as a UTC instant;
- attempt count and next-attempt metadata.

The transaction commits before any Keycloak call. Only the holder of the current unexpired claim performs the next side effect, and state advancement uses compare-and-set on operation ID, state, claim token, and fencing version. Lease duration exceeds configured remote timeouts; a worker renews the lease before a long step. If a worker disappears, a later worker can reclaim after expiry.

Fencing cannot make an external email exactly once. Keycloak uniqueness prevents duplicate user creation, while repeated execute-actions email after an ambiguous response is an accepted at-least-once outcome. Simultaneous same-key requests and competing workers are covered by concurrency tests at every remote boundary.

### Configure Keycloak for deterministic uniqueness and recovery

The PayCore realm configuration is versioned with:

- duplicate emails disabled;
- canonical email used as username;
- email verification enabled;
- an admin-writable, user-hidden `paycore_customer_id` profile attribute;
- required actions `VERIFY_EMAIL` and `UPDATE_PASSWORD` enabled;
- exact redirect URI and bounded execute-action lifespan.

PayCore creates an enabled user with `emailVerified=false`, no credential, canonical username/email, and `paycore_customer_id`. The created Keycloak ID is the external subject.

If create returns conflict or an outcome is ambiguous, the adapter finds the unique canonical username and accepts it only when `paycore_customer_id` equals the saga Customer ID. A missing/mismatched attribute or multiple candidate is reconciliation-required; PayCore never links, modifies, or deletes that user automatically.

The service account has only the realm-management roles required to query/create users and send required-action emails. Secrets are externalized and all administrative responses/tokens are redacted.

### Classify failures without exhausting recoverable work

Failure classes are explicit:

- connection failures, timeouts, HTTP 429, and Keycloak 5xx: retryable with exponential backoff and jitter; honor `Retry-After`;
- create HTTP 409 or timeout after request transmission: ambiguous, perform ownership recovery before retrying create;
- database deadlock or serialization failure: retry the short local transaction with a bounded local retry;
- Keycloak 400 caused by invalid representation, 401/403, missing owned user after linkage, mismatched ownership attribute, or external-link conflict: reconciliation-required and alert operators;
- execute-actions timeout or 5xx: retryable and may resend the safe action email.

Retryable operations do not become permanently failed solely because an attempt threshold is reached. Thresholds raise alerts and increase bounded backoff. Operators can move an unrecoverable Customer to `PROVISIONING_FAILED` only through reconciliation tooling or procedure.

### Apply privacy-preserving rate limits before lookup

Infrastructure applies configurable limits before Customer/operation lookup. Limits include a coarse source/network dimension and a keyed digest of canonical email; responses are the same HTTP 429 shape with `Retry-After` regardless of email existence. Idempotent replay within limits remains supported. Rate-limit storage must work across application instances; the initial PostgreSQL implementation uses short-lived counters or attempt records rather than JVM-only state.

### Keep dependency direction inward

```text
identity/domain
  Customer, CustomerId, CustomerType, CustomerStatus, Email

identity/application
  RegisterCustomer
  ProcessRegistration
  CustomerRepository
  RegistrationOperationRepository
  ExternalIdentityProvisioner
  Transaction boundary and Clock

identity/infrastructure
  web and rate-limit adapters
  scheduled provisioning worker
  JPA entities/repositories and Flyway
  Keycloak Admin REST adapter
```

Domain transitions are framework-free. Ports exchange domain/application values, never JPA entities, HTTP DTOs, Keycloak representations, scheduler types, or Spring transaction types.

## Risks / Trade-offs

- [Registration is eventually completed] -> Return a clear “check your email” message, monitor queue age, and alert on stale operations.
- [Ambiguous email response can duplicate action emails] -> Keep content safe when repeated, rate-limit attempts, and accept at-least-once delivery.
- [Worker lease expires during a slow call] -> Set lease above strict HTTP timeouts, renew before work, fence local advancement, and rely on idempotent external recovery.
- [PostgreSQL polling adds load] -> Index state/next-attempt/lease columns, claim small batches with skip-locked semantics, and measure before adding a broker.
- [Keycloak realm drift breaks uniqueness or attributes] -> Version realm configuration and verify it in startup/contract tests before enabling registration.
- [Generic responses reduce immediate UX feedback] -> Keep account recovery and support reconciliation separate from public registration.
- [Service-account compromise grants user-management access] -> Enforce least privilege, TLS, network controls, audit, and secret rotation.
- [Digest-secret rotation breaks idempotency] -> Version digests and retain old secrets for the full 24-hour window.

## Migration Plan

1. Add Flyway migrations for Customer, external identity, registration operation, worker lease/retry, and rate-limit state using `TIMESTAMPTZ`, constraints, indexes, and optimistic versions.
2. Deploy and verify versioned Keycloak 26.5.2 realm settings, user-profile attribute, SMTP, required actions, redirect allowlist, and least-privileged service account.
3. Deploy PayCore with registration acceptance and worker disabled until migrations, Keycloak connectivity, and email delivery pass health/contract checks.
4. Enable the worker, verify claims/retries/reconciliation metrics, then enable the public registration endpoint.

Rollback disables the endpoint and worker. Persisted Customers, operations, and links remain intact; released Flyway migrations are not edited or removed. Operators reconcile claimed or in-progress operations before re-enabling processing.
