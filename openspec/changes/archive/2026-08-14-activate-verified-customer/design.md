## Context

The registration worker currently completes an `IDENTITY_LINKED` operation by setting the Customer directly from `PROVISIONING` to `ACTIVE`. The OAuth2 login authentication-result converter then resolves `(issuer, subject)` and accepts only an already-active Customer before Spring Security persists a local session.

The `customers.status` check constraint currently has no pending-verification value, and existing `ACTIVE` rows may have live Spring Session records. See `proposal.md` for the behavior change, the two delta specs for observable requirements, and ADR-0006 for the broader wallet sequence.

Spring Security exposes the standard OIDC `email_verified` claim as a nullable Boolean on the validated `OidcUser`. The claim is evidence only after normal issuer, signature, audience/client, nonce, and subject validation has succeeded.

## Goals / Non-Goals

**Goals:**

- Make registration completion persist `PENDING_VERIFICATION` without changing provisioning recovery or operation completion semantics.
- Activate a pending Customer during a linked OIDC login only when validated `email_verified` evidence is exactly `true`.
- Preserve exact `(issuer, subject)` identity resolution and all existing session protections.
- Make repeated and concurrent verified callbacks idempotent.
- Migrate existing active Customers and revoke their pre-migration sessions safely.

**Non-Goals:**

- Provision wallets or ledger accounts; that belongs to `wallet-accounts-and-balances`.
- Add a Keycloak event listener or activate at the exact email-link click instant.
- Use email text as an identity key or compare the OIDC email value with the local email.
- Continuously demote an already-active Customer if a later login reports an unverified email; this change only establishes initial verified activation.
- Change suspended, blocked, provisioning-failed, idle-session, absolute-session, CSRF, logout, or token-refresh behavior.

## Decisions

### Add an explicit pending-verification state

Add `PENDING_VERIFICATION` to the Customer domain and database status constraint. Registration completion changes only `PROVISIONING -> PENDING_VERIFICATION`; `PROVISIONING_FAILED`, `SUSPENDED`, and `BLOCKED` keep their existing meanings.

The Customer domain exposes separate transitions for provisioning completion and verified activation rather than retaining a generic `activate()` method. This prevents registration code from accidentally restoring the old behavior and keeps framework or OIDC concepts out of the domain.

Alternative: reuse `PROVISIONING` until email verification. Rejected because external provisioning is already complete, registration work is terminal, and operational recovery must distinguish external provisioning from waiting for Customer action.

### Resolve identity before considering verification evidence

The authentication-result conversion continues to build `ExternalIdentity` from the validated OIDC issuer and subject. It passes that stable identity plus `Boolean.TRUE.equals(oidcUser.getEmailVerified())` to a login-access application use case.

The use case applies these rules:

1. no linked Customer: deny;
2. `ACTIVE`: return access without another transition;
3. `PENDING_VERIFICATION` plus verified evidence: activate and return access;
4. `PENDING_VERIFICATION` without verified evidence: deny without mutation; and
5. every other status: deny without mutation.

Only a successful result is converted to `CustomerPrincipal`. The existing Spring Security success handler runs afterward to establish the authenticated-at value, inactivity limit, redirect, and server-side session. A failed activation or access decision reaches the existing sanitized OAuth2 failure path and creates no accepted local session.

Alternative: activate in the authentication success handler. Rejected because the authentication object and security context would already have been accepted, making denial and session persistence harder to reason about.

### Serialize activation with a database compare-and-set

Verified activation runs in one local transaction. Persistence resolves the exact external identity and conditionally updates:

```text
PENDING_VERIFICATION -> ACTIVE
```

using the Customer identifier and current status as the compare-and-set predicate. If a concurrent caller already performed the transition, the loser rereads the Customer; `ACTIVE` is the same successful result, while any other state is denied. The transition updates the UTC timestamp and version.

This avoids holding a database lock across OIDC network work because token validation and user loading are complete before the local transaction begins. It also gives equivalent concurrent callbacks one durable state transition without exposing optimistic-lock failures to the browser.

Domain tests remain the primary expression of valid lifecycle transitions. The conditional update and status constraint provide persistence defense in depth.

Alternative: rely only on JPA optimistic locking and return one failed callback. Rejected because equivalent concurrent verified callbacks should converge on the same active result rather than produce a spurious access denial.

### Keep protected-request status enforcement unchanged

After login, every protected request continues to resolve current Customer status and accepts only `ACTIVE`. `PENDING_VERIFICATION` therefore behaves as inactive outside the controlled verified-login use case. Suspension and blocking still revoke all Customer sessions through the existing application boundary.

The verified-login use case is distinct from normal status resolution so a pending Customer cannot become active through an ordinary protected request or repository lookup.

### Use a coordinated breaking migration

Add a new Flyway migration after V3 that:

1. extends the Customer status constraint with `PENDING_VERIFICATION`;
2. updates every existing `ACTIVE` Customer to `PENDING_VERIFICATION`, advancing its version and UTC update instant; and
3. deletes Spring Session rows belonging to migrated Customers, relying on the existing attribute foreign key cascade.

The migration does not change `PROVISIONING`, `PROVISIONING_FAILED`, `SUSPENDED`, or `BLOCKED` rows. Registration operations remain `COMPLETED`; only Customer access status changes.

The migration is incompatible with old application instances because the old enum cannot read the new status and old registration workers still write `ACTIVE`. Deployment therefore uses a coordinated stop-the-world rollout: stop old instances and workers, back up PostgreSQL, run Flyway with the new application, verify migration counts, and then admit traffic.

Alternative: grandfather current active Customers. Rejected because `ACTIVE` would no longer have one consistent meaning. Alternative: infer verification through the Keycloak Admin API during migration. Rejected because migration correctness would depend on a remote system and could not be atomic with local state.

### Preserve privacy and bounded observability

Access denial remains the existing generic OAuth2 failure response. Logs and metric labels must not include email, issuer/subject, tokens, claims, or Customer identifiers. Add bounded counters for pending verified activation success, unverified pending denial, and activation conflict/retry outcomes.

The Keycloak contract must prove that a verified user supplies `email_verified=true` and an unverified user cannot produce accepted pending activation. Missing claim handling is tested explicitly even if the configured required action normally prevents login before verification.

## Risks / Trade-offs

- **[Risk] A Keycloak mapper or realm change omits `email_verified`.** -> Deny pending activation, cover the claim in the versioned Keycloak contract, and alert on bounded denial metrics.
- **[Risk] Concurrent callbacks race with suspension or blocking.** -> Conditional activation permits only the pending state; a competing transition to any other state wins safely and access is denied.
- **[Risk] Migration revokes every current Customer session.** -> Treat this as intentional revalidation, document it in the runbook, and communicate the required new login operationally.
- **[Risk] An old replica writes `ACTIVE` after migration.** -> Prohibit rolling deployment and stop old instances/workers before Flyway runs.
- **[Risk] Activation commits but later HTTP/session persistence fails.** -> A retry resolves the already-active Customer and can create a fresh session; activation remains idempotent.
- **[Trade-off] Local activation occurs on first verified login, not at email-link click time.** -> Accepted to avoid a custom Keycloak event listener.
- **[Trade-off] Wallet provisioning is not yet atomic with activation.** -> This is an explicit staged-delivery gap; `wallet-accounts-and-balances` will extend activation orchestration and backfill wallets for already-active Customers.

## Migration Plan

1. Verify the new enum, domain transitions, login application use case, migration, Keycloak contract, and security integration tests in a production-like PostgreSQL/Keycloak environment.
2. Record counts of Customer statuses and active sessions, then back up PostgreSQL.
3. Stop every old application replica and registration worker.
4. Deploy the new version and allow Flyway to add `PENDING_VERIFICATION`, migrate active Customers, and revoke their sessions.
5. Verify no old process remains, no `ACTIVE` Customer survived the migration, non-active statuses were preserved, and migrated sessions are absent.
6. Resume traffic and monitor pending activation successes, denials, authentication failures, and session creation.

Application rollback is safe only before the migration runs. After migration, the previous application version is incompatible with `PENDING_VERIFICATION`; recovery uses a forward fix or database restore followed by the matching application version. The system must not bulk-restore migrated Customers to `ACTIVE` without verified evidence.
