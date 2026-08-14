## 1. Customer Lifecycle and Registration

- [x] 1.1 Add failing domain tests for `PROVISIONING -> PENDING_VERIFICATION -> ACTIVE`, invalid direct provisioning-to-active activation, repeated activation, and preservation of suspended/blocked transition rules.
- [x] 1.2 Add `PENDING_VERIFICATION` and explicit provisioning-completion and verified-activation domain transitions, removing the generic transition that lets registration activate a Customer directly.
- [x] 1.3 Update registration application and persistence tests so a completed identity link and required-action email request complete the registration operation while leaving the Customer `PENDING_VERIFICATION`.
- [x] 1.4 Change registration completion persistence from `ACTIVE` to `PENDING_VERIFICATION` without altering worker leases, fencing, retries, reconciliation, or generic public responses.

## 2. Verified Login Application Boundary

- [x] 2.1 Add failing application tests for linked active access, pending verified activation, pending false/missing claim denial, unknown identity denial, and suspended/blocked denial using exact `(issuer, subject)` resolution.
- [x] 2.2 Introduce the framework-free verified-login input model and application use case that treats verified-email evidence only as activation evidence and never as an identity key.
- [x] 2.3 Add failing application tests for repeated verified login and concurrent activation convergence so already-active rereads return the same successful Customer access.
- [x] 2.4 Define the minimal persistence port for conditional pending-to-active activation and wire the normal protected-request resolver to remain read-only.

## 3. PostgreSQL Migration and Concurrency

- [x] 3.1 Add failing PostgreSQL/Testcontainers migration tests proving the new status constraint, `ACTIVE -> PENDING_VERIFICATION` data migration, version/timestamp advancement, session deletion, and preservation of every non-active status.
- [x] 3.2 Add Flyway V4 to permit `PENDING_VERIFICATION`, migrate all active Customers, and revoke their Spring Session rows with no edits to released migrations.
- [x] 3.3 Add failing persistence integration tests for atomic linked-identity resolution and conditional activation, including concurrent equivalent callers and idempotent active rereads.
- [x] 3.4 Implement transactional compare-and-set activation with a reread after a lost race, UTC timestamps, version advancement, and sanitized failure behavior.
- [x] 3.5 Add concurrency integration tests where verified activation races with suspension or blocking and prove that at most one valid state transition wins without restoring access to an ineligible Customer.

## 4. OIDC and Session Establishment

- [x] 4.1 Add failing Spring Security integration tests for active login, pending login with `email_verified=true`, pending login with false/missing claim, unknown linkage, session-id rotation, and absence of accepted local session on denial.
- [x] 4.2 Pass `Boolean.TRUE.equals(OidcUser.getEmailVerified())` from the validated OIDC authentication result into the verified-login application use case before creating `CustomerPrincipal` or accepting the security context.
- [x] 4.3 Extend Keycloak contract coverage to prove verified identities expose `email_verified=true` and unverified or missing evidence cannot activate a pending Customer.
- [x] 4.4 Add bounded activation-success, pending-unverified-denial, and activation-race metrics plus log tests proving no email, issuer/subject, Customer identifier, token, cookie, or claim value leaks.
- [x] 4.5 Run authentication regression tests for idle/absolute expiry, refresh, CSRF, logout, multiple sessions, protected-request status reload, and proactive suspension/blocking revocation.

## 5. Operations and Completion Gate

- [x] 5.1 Update registration and authentication runbooks with the coordinated stop-the-world rollout, database backup and count checks, forced reauthentication, monitoring, forward-fix recovery, and prohibition on old replicas after migration.
- [x] 5.2 Run focused domain, registration, persistence, migration, Keycloak contract, and Spring Security tests and record exact command results.
- [x] 5.3 Run the complete `mvnw test` suite with Docker available and resolve every regression.
- [x] 5.4 Validate OpenSpec, review every modified scenario and ADR-0006 boundary, and perform final security/architecture review for claim trust, session acceptance, privacy, migration safety, and concurrency.
