## Purpose

Defines privacy-preserving and retry-safe registration of authentication-ready Customers across PayCore and its external identity provider.

## ADDED Requirements

### Requirement: Registration accepts the common Customer identity
The system SHALL accept an ASCII dot-atom email address no longer than 254 characters, with a local part no longer than 64 characters and a valid DNS-style domain, a Customer type of `INDIVIDUAL` or `BUSINESS`, and a client-provided idempotency key between 1 and 128 bytes. The system SHALL reject control characters, embedded whitespace, quoted local parts, and unsupported internationalized addresses. Complete individual and business profiles SHALL NOT be required by this capability.

#### Scenario: Valid individual registration request
- **WHEN** a client submits a valid email, Customer type `INDIVIDUAL`, and an idempotency key
- **THEN** the system accepts the request for registration processing

#### Scenario: Valid business registration request
- **WHEN** a client submits a valid email, Customer type `BUSINESS`, and an idempotency key
- **THEN** the system accepts the request for registration processing

#### Scenario: Invalid registration input
- **WHEN** the email violates the supported syntax or length, the Customer type is unsupported, or the idempotency key is missing or outside its allowed length
- **THEN** the system responds with HTTP 400 and creates no Customer or external identity

### Requirement: Registration does not handle Customer passwords
PayCore SHALL NOT accept, store, log, or forward a Customer password during registration. The external identity provider SHALL ask the Customer to verify the email address and establish a password through a time-limited action delivered to that email address.

#### Scenario: New external identity is provisioned
- **WHEN** PayCore provisions an external identity for a new Customer
- **THEN** the identity has an unverified email and requires email verification plus password creation before normal login

#### Scenario: Request includes a password field
- **WHEN** a registration request includes a Customer password or credential field
- **THEN** the system responds with HTTP 400 and creates no registration operation, Customer, or external identity without storing, logging, or forwarding that value

### Requirement: Email uniqueness does not enable account enumeration
The system SHALL compare canonicalized emails for uniqueness and SHALL persist the registration decision before returning the same generic HTTP 202 response for a new email and an email already registered in PayCore. The request path SHALL NOT contact the external identity provider. An existing email SHALL NOT create another Customer or trigger an observable duplicate-specific status, body, or external-provider latency difference.

#### Scenario: New email is accepted
- **WHEN** a valid registration request uses an email not registered in PayCore
- **THEN** the system responds with the generic HTTP 202 registration response

#### Scenario: Existing email is submitted
- **WHEN** a valid registration request uses an email already registered in PayCore
- **THEN** the system responds with the same generic HTTP 202 registration response and creates no duplicate Customer or external identity

#### Scenario: Equivalent email representation is submitted
- **WHEN** a valid request differs from an existing email only by surrounding whitespace or letter case
- **THEN** the system treats it as the existing canonical email without revealing that fact

### Requirement: Registration is externally idempotent
The system SHALL associate each idempotency key with a fingerprint of the canonical registration request and SHALL retain a deterministic result for at least 24 hours after the first request.

#### Scenario: Same key and same request are retried
- **WHEN** the same idempotency key is submitted again with the same canonical email and Customer type
- **THEN** the system resumes or returns the original operation result without creating duplicate local or external identities

#### Scenario: Same key is reused with a different request
- **WHEN** an idempotency key is reused with a different canonical email or Customer type
- **THEN** the system responds with HTTP 409 and performs no work for the conflicting request

#### Scenario: Idempotency window has expired
- **WHEN** an idempotency key is submitted after its retained result has expired
- **THEN** the system may treat the key as new while canonical email uniqueness still prevents duplicate Customers

#### Scenario: Concurrent requests use different keys for one email
- **WHEN** concurrent valid requests use different idempotency keys for the same canonical email
- **THEN** at most one Customer and one PayCore-owned external identity are created

### Requirement: Customer activation requires complete identity provisioning
The system SHALL keep a new Customer unavailable for authentication while provisioning is incomplete. It SHALL mark the Customer `ACTIVE` only after the external identity exists, the stable external identity link is persisted, and the external provider accepts the request to deliver email verification and password-creation actions.

#### Scenario: Provisioning completes
- **WHEN** all external identity provisioning steps and local persistence steps complete
- **THEN** the Customer becomes `ACTIVE` and can subsequently be resolved by Customer authentication

#### Scenario: Provisioning is incomplete
- **WHEN** any required provisioning step has not completed
- **THEN** the Customer remains unavailable for authentication

### Requirement: Partial provisioning failures are recoverable
The system SHALL persist enough progress to resume a registration after a process crash, response loss, worker-lease expiry, or transient identity-provider failure without relying on a distributed transaction. At most one unexpired worker claim SHALL own an operation at a time, and stale claims SHALL be recoverable.

#### Scenario: Identity provider is temporarily unavailable
- **WHEN** external identity provisioning or action-email delivery fails transiently
- **THEN** the worker retains recoverable progress and schedules another attempt without changing the generic HTTP 202 result already returned to the client

#### Scenario: Two workers compete for one operation
- **WHEN** multiple workers attempt to claim the same due registration operation
- **THEN** only one worker receives the active lease and may perform the next remote side effect

#### Scenario: Worker claim expires
- **WHEN** a worker stops before completing its claimed step and its lease expires
- **THEN** another worker can claim the operation and recover the ambiguous step safely

#### Scenario: Retry follows an ambiguous external response
- **WHEN** a retry occurs after PayCore cannot determine whether the external identity was created
- **THEN** the system resumes only an external identity carrying the same stable PayCore Customer identifier

#### Scenario: Unrelated external user has the same email
- **WHEN** Keycloak contains a user with the requested email but without the matching PayCore Customer identifier
- **THEN** the system does not link or modify that external user and records the registration for controlled reconciliation

#### Scenario: Permanent provisioning conflict is recorded
- **WHEN** an operation enters reconciliation-required state after the client received HTTP 202
- **THEN** subsequent same-key requests continue returning the generic HTTP 202 result without exposing the internal conflict

### Requirement: External identity links use stable provider identifiers
The system SHALL link a Customer to an external identity by issuer and subject. Email SHALL NOT be used as the external identity link.

#### Scenario: External user creation returns a subject
- **WHEN** PayCore confirms creation or safe recovery of its external user
- **THEN** the system persists a unique `(issuer, subject)` link to the Customer

#### Scenario: Conflicting external identity link is detected
- **WHEN** the issuer and subject are already linked to a different Customer
- **THEN** the system does not activate the new Customer and records the conflict for reconciliation

### Requirement: Registration throttling preserves privacy
The system SHALL apply configurable registration rate limits before Customer or identity-provider lookup and SHALL return the same generic HTTP 429 response and Retry-After guidance regardless of whether the canonical email exists.

#### Scenario: Source exceeds registration rate limit
- **WHEN** a source exceeds the configured registration request limit
- **THEN** the system responds with HTTP 429 before disclosing or processing email existence

#### Scenario: Existing and new emails are throttled
- **WHEN** rate-limited requests target an existing email and a new email
- **THEN** both receive the same HTTP status, body shape, and retry guidance
