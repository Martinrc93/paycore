## 1. Module and Test Foundation

- [x] 1.1 Add minimal test dependencies for architecture verification and Keycloak 26.5.2 contract testing, using Spring Boot-managed versions where available.
- [x] 1.2 Create `identity` domain, application, and infrastructure package boundaries.
- [x] 1.3 Add failing architecture tests enforcing infrastructure -> application -> domain and prohibiting Spring, JPA, HTTP, scheduler, and Keycloak types in the domain.

## 2. Customer Domain

- [x] 2.1 Write failing tests for the supported ASCII dot-atom email grammar, lengths, controls/whitespace, canonical lowercase, and rejected quoted/internationalized addresses.
- [x] 2.2 Write failing tests for Customer creation in `PROVISIONING`, supported types, activation, provisioning failure, and invalid transitions.
- [x] 2.3 Implement minimal framework-free `Email`, `CustomerId`, `CustomerType`, `CustomerStatus`, and `Customer` types.
- [x] 2.4 Add Clock-driven tests and implementation for UTC creation/update instants without `LocalDateTime`, `Date`, or `Calendar`.

## 3. Idempotent Acceptance

- [x] 3.1 Write failing application tests for new registration, generic duplicate suppression, same-key replay, different-payload key conflict, expired-key reuse, and invalid key length.
- [x] 3.2 Define framework-free registration input/output models and persistence/transaction ports.
- [x] 3.3 Implement versioned keyed idempotency digests, request fingerprinting, 24-hour retention, and key rotation coverage without storing raw keys.
- [x] 3.4 Implement the acceptance transaction that atomically records an operation plus new `PROVISIONING` Customer or duplicate-suppressed terminal result.
- [x] 3.5 Add concurrency tests proving same/different keys for one canonical email create at most one Customer and always return the generic HTTP 202 result.

## 4. PostgreSQL Schema and Adapters

- [x] 4.1 Add a Flyway migration for Customer, external identity, registration operation, lease/retry, idempotency retention, and distributed rate-limit state with `TIMESTAMPTZ`, foreign keys, unique constraints, indexes, and optimistic/fencing versions.
- [x] 4.2 Write PostgreSQL Testcontainers tests for mapping, canonical-email uniqueness, `(issuer, subject)` uniqueness, operation-state transitions, and UTC preservation.
- [x] 4.3 Implement separate JPA entities/repositories and mapping adapters without exposing persistence models inward.
- [x] 4.4 Add transaction tests proving operation+Customer, identity-link+state, and activation+completion writes are atomic.
- [x] 4.5 Implement and test cleanup that retains completed idempotency results for at least 24 hours and never deletes reconciliation-required operations automatically.

## 5. Worker Claiming and Saga State Machine

- [x] 5.1 Write failing state-machine tests for `PENDING_IDENTITY`, `IDENTITY_LINKED`, `COMPLETED`, `DUPLICATE_SUPPRESSED`, and `RECONCILIATION_REQUIRED` transitions.
- [x] 5.2 Write failing PostgreSQL concurrency tests for skip-locked claims, one active lease, fencing-version increments, compare-and-set advancement, lease renewal, and stale-lease recovery.
- [x] 5.3 Implement the scheduled in-process worker and claim repository using short transactions that commit before remote calls.
- [x] 5.4 Add tests proving simultaneous same-key requests and competing workers perform at most one user-create attempt before any ambiguous-recovery check.
- [x] 5.5 Add parameterized crash/restart tests after acceptance, claim, user create, identity-link commit, email request, and activation commit.

## 6. Keycloak Provisioning Adapter

- [x] 6.1 Version Keycloak 26.5.2 realm configuration with duplicate emails disabled, canonical email username, email verification, required actions, exact redirects, and admin-only `paycore_customer_id` user-profile attribute.
- [x] 6.2 Add environment-backed Admin API/service-account/execute-action configuration with no committed secrets.
- [x] 6.3 Write failing Keycloak contract tests for least-privileged service authentication and user creation with no credentials, `emailVerified=false`, required actions, canonical username, and ownership attribute.
- [x] 6.4 Implement the Keycloak Admin REST adapter and map created user ID to external subject.
- [x] 6.5 Add tests and implementation for HTTP 409/ambiguous-create recovery that accepts only the unique matching ownership attribute.
- [x] 6.6 Add tests proving unrelated or ambiguous same-email users are never linked, modified, or deleted and move the operation to reconciliation-required.
- [x] 6.7 Add tests and implementation for time-limited execute-actions email with accepted at-least-once resend semantics.

## 7. Failure Classification and Retry

- [x] 7.1 Write parameterized tests classifying connection/timeouts, 429/Retry-After, 5xx, ambiguous create, 400, 401/403, missing owned user, database deadlock/serialization, and identity-link conflict.
- [x] 7.2 Implement exponential backoff with jitter and no automatic terminal cutoff for retryable operations; raise sanitized alerts after configured thresholds.
- [x] 7.3 Implement reconciliation-required handling for configuration, ownership, missing-user, and integrity conflicts without changing the original public HTTP 202 result.
- [x] 7.4 Verify logs and metrics never contain raw idempotency keys, emails as high-cardinality labels, credentials, service tokens, external response bodies, or partial identity details.

## 8. Web and Rate-Limit Adapters

- [x] 8.1 Write failing web tests for valid individual/business requests, precise email rules, mandatory 1-128 byte idempotency key, unknown credential fields, and strict request validation.
- [x] 8.2 Implement the registration DTO/controller and reject passwords or credentials with HTTP 400 before creating any operation.
- [x] 8.3 Return the same generic HTTP 202 status/body for new, duplicate-suppressed, completed, and reconciliation-required same-key operations.
- [x] 8.4 Add failing distributed rate-limit tests proving limits run before email lookup and produce identical HTTP 429 body/Retry-After for existing and new emails.
- [x] 8.5 Implement PostgreSQL-backed source and canonical-email-digest rate limits with environment-backed thresholds.
- [x] 8.6 Add timing-oriented integration tests that stub slow/unavailable Keycloak and prove valid request latency/status do not depend on external calls or email existence.

## 9. Operational Documentation and Completion

- [x] 9.1 Document queue-age monitoring, lease/backoff tuning, 24-hour idempotency retention, digest-secret rotation, reconciliation, SMTP/Keycloak prerequisites, rollout, and rollback.
- [x] 9.2 Run focused domain/application tests and record exact results (28 tests, 0 failures, 0 errors, 0 skipped; 2026-08-08).
- [x] 9.3 Run PostgreSQL and Keycloak integration/contract/concurrency tests with Docker and record exact results (61 tests, 0 failures, 0 errors, 0 skipped; 2026-08-08).
- [x] 9.4 Run full `mvnw test` and resolve every regression (105 tests, 0 failures, 0 errors, 0 skipped; 2026-08-08).
- [x] 9.5 Review every customer-registration scenario, architecture boundary, UTC rule, migration, privacy path, concurrency path, and credential-leakage risk (no Critical/Important/Medium findings after fixes; 2026-08-08).
- [x] 9.6 Perform independent code/security review and commit only after all required tests and reviews pass (no Critical/Important/Medium findings after fixes; 2026-08-08).
