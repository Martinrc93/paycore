# Customer Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Authenticate registered Customers through Keycloak OIDC while the browser holds only an opaque, secure PayCore session cookie.

**Architecture:** Extend the existing hexagonal `identity` module. Framework-free application services resolve `(issuer, subject)`, enforce Customer status, and request session revocation through ports; Spring Security, OAuth2 Client, Spring Session JDBC, HTTP, Keycloak, and PostgreSQL remain infrastructure adapters. V1 remains immutable and a new V2 migration owns only Spring Session tables.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Security 7.1.0, Spring OAuth2 Client, Spring Session JDBC, PostgreSQL 17, Flyway, Keycloak 26.5.2, JUnit 5, MockMvc, Testcontainers, ArchUnit.

## Global Constraints

- OpenSpec `identity/customer-authentication` is the behavioral source of truth.
- The browser receives no access token, refresh token, ID-provider credentials, or password.
- Resolve identity only by exact OIDC issuer and subject; never use email as an identity key.
- Session idle timeout is 30 minutes and absolute lifetime is 8 hours from successful authentication.
- Cookie name is `__Host-paycore-session`, with Secure, HttpOnly, SameSite=Lax, Path=/, and no Domain.
- Unsafe methods, including logout, require a session-bound CSRF token.
- All instants use `Instant`, UTC `TIMESTAMPTZ`, and an injected `Clock`.
- Domain and application code do not depend on Spring, JPA, HTTP, Keycloak, or session persistence types.
- Do not edit V1; add V2 for Spring Session JDBC.
- Do not commit authentication changes unless the user explicitly requests a commit.

---

### Task 1: Security And Configuration Baseline

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Modify: `deploy/keycloak/paycore-realm.json`
- Modify: `src/test/java/dev/martin/paycore/identity/IdentityArchitectureTest.java`
- Create: `src/test/java/dev/martin/paycore/identity/infrastructure/security/AuthenticationConfigurationTest.java`

**Interfaces:**
- Consumes: existing Spring Boot dependency management and Keycloak 26.5.2 realm.
- Produces: OAuth2 Client and Spring Session dependencies, environment-backed registration `paycore`, secure cookie/session defaults, and architecture enforcement.

- [ ] **Step 1: Add failing architecture and configuration tests**

  Add ArchUnit rules preventing application classes from depending on `org.springframework..`, `jakarta.persistence..`, `jakarta.servlet..`, or `org.keycloak..`. Add an application-context test asserting a `ClientRegistration` named `paycore`, a 30-minute servlet session timeout, disabled JDBC schema initialization, and the expected cookie settings.

- [ ] **Step 2: Verify RED**

  Run: `.\mvnw.cmd -Dtest=IdentityArchitectureTest,AuthenticationConfigurationTest test`

  Expected: compilation or context failure because OAuth2 Client, Spring Session JDBC, and authentication properties are absent.

- [ ] **Step 3: Add the minimal baseline**

  Add Boot-managed dependencies `spring-boot-starter-oauth2-client`, `spring-session-jdbc`, and test-scoped `spring-security-test`. Configure:

  ```properties
  spring.session.jdbc.initialize-schema=never
  server.servlet.session.timeout=30m
  server.servlet.session.cookie.name=__Host-paycore-session
  server.servlet.session.cookie.secure=true
  server.servlet.session.cookie.http-only=true
  server.servlet.session.cookie.same-site=lax
  server.servlet.session.cookie.path=/
  spring.security.oauth2.client.registration.paycore.client-id=${PAYCORE_OIDC_CLIENT_ID:paycore-bff}
  spring.security.oauth2.client.registration.paycore.client-secret=${PAYCORE_OIDC_CLIENT_SECRET:}
  spring.security.oauth2.client.registration.paycore.authorization-grant-type=authorization_code
  spring.security.oauth2.client.registration.paycore.redirect-uri=${PAYCORE_OIDC_REDIRECT_URI:{baseUrl}/login/oauth2/code/{registrationId}}
  spring.security.oauth2.client.registration.paycore.scope=openid
  spring.security.oauth2.client.provider.paycore.issuer-uri=${PAYCORE_OIDC_ISSUER_URI:http://localhost:8081/realms/paycore}
  paycore.authentication.enabled=${PAYCORE_AUTHENTICATION_ENABLED:false}
  paycore.authentication.success-uri=${PAYCORE_AUTHENTICATION_SUCCESS_URI:/}
  paycore.authentication.logout-path=${PAYCORE_AUTHENTICATION_LOGOUT_PATH:/bff/auth/logout}
  ```

  `logout-path` identifies the local PayCore logout endpoint only. It is not a Keycloak end-session URL and does not initiate Keycloak SSO logout.

  Add a confidential `paycore-bff` realm client with exact redirect URI, exact same-site origin, standard flow, PKCE `S256`, direct grants disabled, service accounts disabled, and bounded access/session lifetimes. Keep its secret environment-backed.

- [ ] **Step 4: Verify GREEN**

  Run: `.\mvnw.cmd -Dtest=IdentityArchitectureTest,AuthenticationConfigurationTest test`

  Expected: PASS.

---

### Task 2: Framework-Free Customer Access Resolution

**Files:**
- Create: `src/main/java/dev/martin/paycore/identity/domain/model/ExternalIdentity.java`
- Create: `src/main/java/dev/martin/paycore/identity/application/authentication/CustomerAccess.java`
- Create: `src/main/java/dev/martin/paycore/identity/application/authentication/ResolveCustomerAccess.java`
- Create: `src/main/java/dev/martin/paycore/identity/application/authentication/ResolveCustomerAccessService.java`
- Create: `src/main/java/dev/martin/paycore/identity/application/port/out/CustomerAccessRepository.java`
- Create: `src/main/java/dev/martin/paycore/identity/application/port/out/SessionRevocationPort.java`
- Create: `src/test/java/dev/martin/paycore/identity/application/authentication/ResolveCustomerAccessServiceTest.java`

**Interfaces:**
- Produces: `Optional<CustomerAccess> ResolveCustomerAccess.resolve(ExternalIdentity identity)` and `Optional<CustomerAccess> ResolveCustomerAccess.resolve(CustomerId customerId)`, where `CustomerAccess` contains stable `CustomerId`, current `CustomerStatus`, and `isActive()`.
- Produces: `void SessionRevocationPort.revokeCurrent(String sessionId)` and `void SessionRevocationPort.revokeAll(CustomerId customerId)`.

- [ ] **Step 1: Write failing access-resolution tests**

  Cover exact issuer/subject success, same subject under two issuers, unknown link, every non-active status, and an email claim changing without affecting lookup. Use an in-memory fake keyed only by `ExternalIdentity`; assert returned Customer IDs and access denial, not fake call counts.

- [ ] **Step 2: Verify RED**

  Run: `.\mvnw.cmd -Dtest=ResolveCustomerAccessServiceTest test`

  Expected: compilation failure because the authentication application API does not exist.

- [ ] **Step 3: Implement the minimal framework-free API**

  `ExternalIdentity` validates nonblank issuer and subject. `CustomerAccessRepository.findByExternalIdentity` and `.findByCustomerId` return stable ID plus current status. `ResolveCustomerAccessService` exposes both lookups without accepting email; login accepts only `CustomerAccess.isActive()`, while protected requests use the Customer-ID lookup for current status. Add the framework-free revocation port without an implementation yet.

- [ ] **Step 4: Verify GREEN**

  Run: `.\mvnw.cmd -Dtest=ResolveCustomerAccessServiceTest,IdentityArchitectureTest test`

  Expected: PASS.

---

### Task 3: PostgreSQL Session Persistence And Revocation

**Files:**
- Create: `src/main/resources/db/migration/V2__create_customer_sessions.sql`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/persistence/CustomerAccessPersistenceAdapter.java`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/session/SpringSessionRevocationAdapter.java`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/session/AuthenticationSessionConfiguration.java`
- Create: `src/test/java/dev/martin/paycore/identity/infrastructure/persistence/CustomerAccessPersistenceAdapterTest.java`
- Create: `src/test/java/dev/martin/paycore/identity/infrastructure/session/SpringSessionRevocationAdapterTest.java`

**Interfaces:**
- Consumes: `CustomerAccessRepository`, `SessionRevocationPort`, V1 `customers` and `external_identities`.
- Produces: PostgreSQL-backed exact identity lookup and principal-indexed session deletion.

- [ ] **Step 1: Write failing PostgreSQL integration tests**

  Use `postgres:17` with `@ServiceConnection`. Verify exact issuer/subject joins, issuer isolation for equal subjects, active and inactive status reads, two independent sessions for one Customer, current-only deletion, all-session deletion, attribute cascade cleanup, and expired-row cleanup.

- [ ] **Step 2: Verify RED**

  Run: `.\mvnw.cmd -Dtest=CustomerAccessPersistenceAdapterTest,SpringSessionRevocationAdapterTest test`

  Expected: Flyway or compilation failure because V2 and adapters do not exist.

- [ ] **Step 3: Add V2 and adapters**

  Copy the Spring Session JDBC PostgreSQL schema compatible with the resolved dependency into V2: `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`, unique `SESSION_ID`, expiry index, principal index, and cascading attribute foreign key. Configure `JdbcIndexedSessionRepository` with a 30-minute default maximum inactive interval and use `FindByIndexNameSessionRepository.findByPrincipalName(customerId.value().toString())` for all-session revocation. Delete only the supplied ID for current-session revocation.

- [ ] **Step 4: Verify GREEN**

  Run: `.\mvnw.cmd -Dtest=CustomerAccessPersistenceAdapterTest,SpringSessionRevocationAdapterTest test`

  Expected: PASS against PostgreSQL 17.

---

### Task 4: OIDC Login And Opaque Session Establishment

**Files:**
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/security/CustomerPrincipal.java`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/security/CustomerOidcAuthenticationSuccessHandler.java`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/security/OidcAudienceValidator.java`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/security/AuthenticationSecurityConfiguration.java`
- Create: `src/test/java/dev/martin/paycore/identity/infrastructure/security/OidcLoginSecurityTest.java`

**Interfaces:**
- Consumes: `ResolveCustomerAccess`, `OAuth2AuthorizedClientRepository`, `SecurityContextRepository`, configured client ID and `Clock`.
- Produces: a serializable local `CustomerPrincipal` named by stable Customer ID and session attributes `paycore.authenticated-at` and the Spring Session principal index.

- [ ] **Step 1: Write failing MockMvc OIDC tests**

  Verify `/oauth2/authorization/paycore` redirects with state, nonce, and `code_challenge_method=S256`; successful callback rotates a pre-login session ID; unlinked/inactive identities fail with sanitized 403; provider failure creates no authenticated local session; cookie attributes are exact; response bodies and browser-readable cookies contain no access or refresh token.

- [ ] **Step 2: Verify RED**

  Run: `.\mvnw.cmd -Dtest=OidcLoginSecurityTest test`

  Expected: security behavior differs because OIDC login and local Customer adaptation are not configured.

- [ ] **Step 3: Implement login establishment**

  Enable OAuth2 Login only when `paycore.authentication.enabled=true`. Configure `DefaultOAuth2AuthorizationRequestResolver` with `OAuth2AuthorizationRequestCustomizers.withPkce()` so the confidential client always emits S256 PKCE. Add an ID-token audience validator requiring the configured client ID in `aud`; rely on provider metadata/JWKS for issuer and signature and Spring OIDC processing for nonce and subject validation. On success, resolve exact issuer/subject before accepting local authentication, replace the OIDC browser principal with `CustomerPrincipal`, persist the resulting `SecurityContext`, retain the authorized client only in the server-side session, set authenticated-at once from `Clock`, and redirect only to the configured same-site success URI.

- [ ] **Step 4: Verify GREEN**

  Run: `.\mvnw.cmd -Dtest=OidcLoginSecurityTest,ResolveCustomerAccessServiceTest test`

  Expected: PASS.

---

### Task 5: Refresh, Absolute Lifetime, And Current Status

**Files:**
- Create: `src/main/java/dev/martin/paycore/identity/application/authentication/SessionLifetimePolicy.java`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/security/SessionLifetimeFilter.java`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/security/CustomerStatusFilter.java`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/security/OAuth2RefreshFilter.java`
- Modify: `src/main/java/dev/martin/paycore/identity/domain/model/Customer.java`
- Modify: `src/main/java/dev/martin/paycore/identity/application/port/out/CustomerRepository.java`
- Modify: `src/main/java/dev/martin/paycore/identity/infrastructure/persistence/CustomerPersistenceAdapter.java`
- Create: `src/main/java/dev/martin/paycore/identity/application/authentication/ChangeCustomerStatusService.java`
- Create: `src/test/java/dev/martin/paycore/identity/application/authentication/SessionLifetimePolicyTest.java`
- Create: `src/test/java/dev/martin/paycore/identity/application/authentication/ChangeCustomerStatusServiceTest.java`
- Create: `src/test/java/dev/martin/paycore/identity/infrastructure/security/ProtectedSessionSecurityTest.java`
- Create: `src/test/java/dev/martin/paycore/identity/infrastructure/session/ConcurrentStatusRevocationTest.java`

**Interfaces:**
- Produces: `Instant SessionLifetimePolicy.absoluteExpiry(Instant authenticatedAt)` and `Duration remainingIdleTimeout(Instant authenticatedAt)` capped at `min(30 minutes, remaining absolute lifetime)`.
- Produces: `ChangeCustomerStatusService.suspend(CustomerId)` and `.block(CustomerId)`, each persisting status then revoking all Customer sessions.

- [ ] **Step 1: Write failing Clock-based and protected-request tests**

  With a fixed/mutable `Clock`, cover idle expiry at 30 minutes, absolute expiry at 8 hours, activity and refresh not moving authenticated-at, repository timeout capped to remaining lifetime, refresh success, refresh rejection invalidating the session, status loaded on every protected request, first inactive request returning 403 and revoking all sessions, reuse returning 401, proactive suspension/blocking revocation, and requests racing with suspension never succeeding after the transition commits.

- [ ] **Step 2: Verify RED**

  Run: `.\mvnw.cmd -Dtest=SessionLifetimePolicyTest,ChangeCustomerStatusServiceTest,ProtectedSessionSecurityTest,ConcurrentStatusRevocationTest test`

  Expected: compilation failure because lifetime, refresh, status filters, and transitions do not exist.

- [ ] **Step 3: Implement lifetime and status enforcement**

  Add legal `ACTIVE -> SUSPENDED` and `ACTIVE -> BLOCKED` domain transitions using injected instants. Persist through `CustomerRepository.save`. Before protected processing, reject an absolute-expired session with 401 and invalidate it; otherwise cap its max inactive interval. On every protected request, resolve current Customer status through the application boundary; if inactive, revoke all indexed sessions, invalidate the request session, and return sanitized 403. Use a server-side `OAuth2AuthorizedClientManager` to refresh expired access tokens; on refresh failure remove the authorized client, invalidate the session, and return 401 without token details.

- [ ] **Step 4: Verify GREEN**

  Run: `.\mvnw.cmd -Dtest=SessionLifetimePolicyTest,ChangeCustomerStatusServiceTest,ProtectedSessionSecurityTest,ConcurrentStatusRevocationTest test`

  Expected: PASS.

---

### Task 6: Public Allowlist, CSRF, And Current Logout

**Files:**
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/web/AuthenticationController.java`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/security/JsonAuthenticationEntryPoint.java`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/security/JsonAccessDeniedHandler.java`
- Create: `src/test/java/dev/martin/paycore/identity/infrastructure/security/BrowserSecurityTest.java`

**Interfaces:**
- Produces: public GET `/bff/auth/csrf`, public OAuth2 initiation/callback endpoints, protected GET `/bff/auth/session`, and CSRF-protected POST `/bff/auth/logout`.

- [ ] **Step 1: Write failing browser-security tests**

  Verify only health, registration, CSRF bootstrap, OAuth2 initiation, and callback are public; protected BFF requests return sanitized 401 without a session; valid CSRF allows unsafe processing; missing, invalid, or another session's CSRF returns 403 without state change; logout deletes only the current session and authorized client, expires the cookie, leaves another Customer session valid, and does not redirect to Keycloak logout.

- [ ] **Step 2: Verify RED**

  Run: `.\mvnw.cmd -Dtest=BrowserSecurityTest test`

  Expected: route/status/CSRF failures because the BFF endpoints and handlers do not exist.

- [ ] **Step 3: Implement minimal BFF security endpoints**

  Use a session-backed `CsrfTokenRepository` and return only token/header/parameter names from `/bff/auth/csrf`. Keep CSRF enabled for every unsafe method. Implement current logout with Spring Security logout handlers plus current-session revocation, authorized-client removal, SecurityContext clearing, session invalidation, and cookie deletion. Return fixed JSON error shapes such as `{"code":"unauthorized"}` and `{"code":"forbidden"}` without internal causes.

- [ ] **Step 4: Verify GREEN**

  Run: `.\mvnw.cmd -Dtest=BrowserSecurityTest,OidcLoginSecurityTest,ProtectedSessionSecurityTest test`

  Expected: PASS.

---

### Task 7: Keycloak Contract And Operations

**Files:**
- Modify: `deploy/keycloak/paycore-realm.json`
- Create: `src/main/java/dev/martin/paycore/identity/infrastructure/security/AuthenticationMetrics.java`
- Create: `src/test/java/dev/martin/paycore/identity/infrastructure/keycloak/KeycloakAuthenticationContractTest.java`
- Create: `src/test/java/dev/martin/paycore/identity/infrastructure/security/AuthenticationObservabilityTest.java`
- Create: `docs/runbooks/customer-authentication.md`

**Interfaces:**
- Consumes: Keycloak 26.5.2 container, OIDC discovery/JWKS/authorization/token endpoints, Micrometer `MeterRegistry`.
- Produces: sanitized counters for login failure, refresh failure, Customer denial, session revocation, and cleanup; reproducible deployment and rollback guidance.

- [ ] **Step 1: Write failing contract and observability tests**

  Start Keycloak 26.5.2 with the versioned realm. Exercise discovery and JWKS; create an enabled test user with a known test-only credential through the admin API; complete Authorization Code + PKCE through Keycloak endpoints; prove invalid credentials do not produce a code; rotate the realm signing key through the admin API and verify JWKS overlaps old and new keys during the tested transition. Capture logs/metrics for failures and assert cookies, credentials, authorization codes, access tokens, refresh tokens, and token fragments are absent.

- [ ] **Step 2: Verify RED**

  Run: `.\mvnw.cmd -Dtest=KeycloakAuthenticationContractTest,AuthenticationObservabilityTest test`

  Expected: contract or compilation failure because the BFF client/metrics are incomplete.

- [ ] **Step 3: Complete realm, metrics, and runbook**

  Keep all realm/client settings non-secret and inject the confidential client secret at deployment. Increment low-cardinality counters only; log operation category and sanitized reason code without request headers, cookies, credentials, claims, or tokens. Document HTTPS and trusted-proxy requirements, exact issuer/client/redirect/logout variables, secret rotation, database least privilege and encrypted backups, cleanup monitoring, incompatible-deployment session invalidation, rollout gates, and rollback that disables login and invalidates sessions without reverting migrations.

- [ ] **Step 4: Verify GREEN**

  Run: `.\mvnw.cmd -Dtest=KeycloakAuthenticationContractTest,AuthenticationObservabilityTest test`

  Expected: PASS with Keycloak 26.5.2.

---

### Task 8: Completion Gates

**Files:**
- Modify: `openspec/changes/authenticate-customer/tasks.md`

**Interfaces:**
- Consumes: all prior deliverables.
- Produces: exact verification evidence and a complete 38-task OpenSpec checklist.

- [ ] **Step 1: Run focused tests**

  Run all domain/application authentication tests, PostgreSQL session/persistence tests, Spring Security tests, Keycloak contract tests, and `IdentityArchitectureTest`. Record exact test totals in OpenSpec task 8.1.

- [ ] **Step 2: Run the full suite**

  Run: `.\mvnw.cmd test`

  Expected: all tests pass with 0 failures, 0 errors, and 0 skipped. Record the exact total in task 8.2.

- [ ] **Step 3: Validate requirements and architecture**

  Run: `openspec validate "authenticate-customer"`

  Review each scenario in `specs/identity/customer-authentication/spec.md`, ADR-0001, ADR-0002, ADR-0004, migration ordering, and all 38 tasks. Mark 8.3 only when each has implementation or test evidence.

- [ ] **Step 4: Run independent security review**

  Review credential/token leakage, session fixation, CSRF bypass, cross-session tokens, cookie scope, refresh rejection, absolute lifetime, status races, privilege boundaries, logs, metrics, and failure disclosure. Resolve every Critical, Important, or Medium finding and rerun affected plus full tests before marking 8.4.

- [ ] **Step 5: Inspect final repository state**

  Run `git status --short`, `git diff --check`, and inspect the complete diff. Keep the branch uncommitted unless the user explicitly requests a commit.
