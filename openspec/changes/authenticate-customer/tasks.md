## 1. Prerequisites and Security Baseline

- [x] 1.1 Complete and apply the separate `register-customer` OpenSpec change that owns the stable Customer identifier, registration status transitions, and `(issuer, subject)` linkage (`c886f6f`; 2026-08-08).
- [x] 1.2 Add Spring OAuth2 Client, Spring Session JDBC, and affected security-test dependencies using versions managed by Spring Boot 4.1.0.
- [x] 1.3 Select and pin the Keycloak version used for local and contract testing, then document issuer, client, redirect, logout, and secret configuration through environment-backed properties.
- [x] 1.4 Add architecture tests that enforce infrastructure -> application -> domain dependencies and prohibit Spring, JPA, HTTP, and Keycloak types in the domain.

## 2. Domain and Application Access Resolution

- [x] 2.1 Write failing unit tests for resolving a linked active Customer by exact issuer and subject, including identities with the same subject under different issuers.
- [x] 2.2 Write failing unit tests for unknown links and inactive Customers, ensuring mutable email claims are never used as identity keys.
- [x] 2.3 Introduce the minimal framework-free identity value objects, Customer access result, application input port, and persistence output port needed to satisfy the tests.
- [x] 2.4 Define a framework-free session-revocation output port for revoking the current session or all sessions of a Customer without leaking Spring Session types inward.

## 3. PostgreSQL Persistence and Sessions

- [x] 3.1 Verify the existing V1 Flyway migration supplies the external identity link with uniqueness on `(issuer, subject)` and the Customer linkage owned by `register-customer` (verified 2026-08-08).
- [x] 3.2 Add a Flyway migration for the PostgreSQL Spring Session JDBC schema and required expiry/principal indexes; disable automatic session-schema initialization.
- [x] 3.3 Implement and integration-test the persistence adapter that resolves external identity and current Customer status using PostgreSQL Testcontainers.
- [x] 3.4 Configure Spring Session JDBC with a 30-minute idle timeout and principal indexing by stable local Customer identifier.
- [x] 3.5 Implement the session-revocation adapter and integration-test current-session revocation, all-session revocation, independent concurrent sessions, repository-level absolute expiration, and expired-row/token cleanup.

## 4. Keycloak OIDC and BFF Session Establishment

- [x] 4.1 Write failing security integration tests for login initiation, state/nonce/PKCE authorization parameters, callback success, callback failure, and session-id rotation.
- [x] 4.2 Configure PayCore as a confidential OIDC client using Authorization Code flow with PKCE and validate issuer, subject, nonce, and configured client/audience expectations.
- [x] 4.3 Adapt validated OIDC issuer and subject into the application access-resolution use case before an authenticated local session is accepted.
- [x] 4.4 Store the SecurityContext and OAuth authorized client only in server-side HttpSession and verify responses, cookies, logs, and browser-readable state do not expose access or refresh tokens.
- [x] 4.5 Configure `__Host-paycore-session` as Secure, HttpOnly, SameSite=Lax, Path=/, without Domain, and verify those attributes in integration tests.
- [x] 4.6 Configure server-side access-token refresh and test successful renewal plus session invalidation when Keycloak rejects or revokes the refresh token.

## 5. Session Lifetime and Customer Status Enforcement

- [x] 5.1 Write failing tests with an injected Clock for 30-minute idle expiration, fixed 8-hour expiration measured from successful authentication, and the rule that token refresh does not extend absolute lifetime.
- [x] 5.2 Enforce absolute expiration before protected request processing and cap repository inactivity expiry to the remaining absolute lifetime so stored sessions cannot remain active past the deadline.
- [x] 5.3 Add tests proving Customer status is reloaded on every protected request, the first inactive request returns HTTP 403, and reuse after revocation returns HTTP 401.
- [x] 5.4 Revalidate Customer status through the application boundary on every protected request and revoke all indexed sessions when an inactive Customer is detected.
- [x] 5.5 Wire the Customer status-transition use case to the session-revocation port so suspension or blocking proactively removes every indexed Customer session.
- [x] 5.6 Add integration tests for proactive status-transition revocation and concurrent protected requests racing with suspension.

## 6. Browser Security and Logout

- [x] 6.1 Define an explicit allowlist of public authentication endpoints and require a valid local session for protected BFF endpoints.
- [x] 6.2 Expose the session-bound CSRF token to the same-site SPA and require it on unsafe HTTP methods, including logout.
- [x] 6.3 Add security tests proving valid CSRF requests proceed and missing, invalid, or cross-session CSRF tokens return HTTP 403 without state changes.
- [x] 6.4 Implement current-session logout that removes the session and authorized client, expires the cookie, preserves other Customer sessions, and does not implicitly end Keycloak SSO.
- [x] 6.5 Return sanitized HTTP 401 responses for missing/invalid sessions and HTTP 403 for valid external identities that cannot access a local Customer, without disclosing linkage, status, OIDC, or token details.

## 7. Keycloak Contract and Operational Verification

- [x] 7.1 Version reproducible non-secret Keycloak realm/client configuration with confidential client, exact redirect URIs, allowed origins, PKCE, and bounded token lifetimes (`KeycloakAuthenticationContractTest`; 2026-08-09).
- [x] 7.2 Add a Keycloak contract or container test covering a successful authorization flow, invalid credentials, issuer/JWKS discovery, and signing-key rotation or overlapping keys (`KeycloakAuthenticationContractTest` on Keycloak 26.5.2; 1 test, 0 failures; 2026-08-09).
- [x] 7.3 Add sanitized metrics and logs for login failures, refresh failures, session counts, cleanup, and Customer-access denials without recording cookies, credentials, or tokens (`AuthenticationObservabilityTest`; 2 tests, 0 failures; full suite 185 tests, 0 failures; 2026-08-09).
- [x] 7.4 Document HTTPS, database encryption/backup expectations, client-secret handling, session invalidation during incompatible deployments, and rollback behavior (`docs/runbooks/customer-authentication.md`; self-reviewed against Task 7 brief; 2026-08-09).

## 8. Completion Verification

- [x] 8.1 Run focused domain, application, persistence, and Spring Security tests and record the exact results (2026-08-09: 88 focused executions with 0 failures/errors/skips; exact commands and area totals in `docs/verification/customer-authentication-task-8.md`).
- [x] 8.2 Run the full `mvnw test` suite with Docker available and resolve all regressions (2026-08-09: `./mvnw.cmd test`; 191 tests, 0 failures, 0 errors, 0 skipped; durable evidence in `docs/verification/customer-authentication-task-8.md`).
- [x] 8.3 Review the implementation against every `identity/customer-authentication` scenario, the architecture ADRs, UTC requirements, Flyway rules, and the OpenSpec task list (the final whole-branch review found authenticated-registration CSRF and OIDC reauthentication lifetime defects plus ambiguous replicated-gauge semantics; the final fix wave added named integration and meter-metadata regressions and refreshed all scenario/architecture/operations evidence in `docs/verification/customer-authentication-task-8.md`).
- [x] 8.4 Perform a security-focused code review for credential/token leakage, session fixation, CSRF bypass, cookie scope, privilege boundaries, and failure-message disclosure (after the initial review, two remediation rounds, and independent fix re-review 1, the final whole-branch review found two Important and one Medium issue; the final fix wave resolved all three under strict RED/GREEN evidence and records the complete durable chronology in `docs/reviews/customer-authentication-task-8.md` and `docs/verification/customer-authentication-task-8.md`; no Critical, Important, or Medium issue remains in final-wave self-review).
