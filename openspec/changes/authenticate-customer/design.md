## Context

See `proposal.md` for motivation and `specs/identity/customer-authentication/spec.md` for the behavior contract.

PayCore is a Spring Boot 4.1.0 application with Spring Security 7.1.0, PostgreSQL, Flyway, and an implemented `identity` registration capability. Flyway migration V1 owns the Customer and stable `(issuer, subject)` external identity schema. ADR-0001 assigns customer identity to the `identity` module, and ADR-0002 requires infrastructure dependencies to point inward through application ports while domain objects remain independent of Spring, HTTP, JPA, and Keycloak.

The browser client is a SPA served from the same site as the PayCore BFF. A registered Customer and a stable link from the Keycloak identity to that Customer are prerequisites; the completed `register-customer` change owns and supplies that model and link. This change consumes those existing boundaries and does not duplicate their schema or provisioning behavior.

## Goals / Non-Goals

**Goals:**

- Establish a BFF security boundary in which the browser holds only an opaque session identifier.
- Use standards-based OIDC login while preserving a framework-free Customer access decision.
- Persist sessions across application restarts and support multiple application instances without introducing Redis.
- Enforce local Customer state, idle expiry, absolute expiry, CSRF protection, and targeted logout.
- Keep Keycloak and Spring concerns in infrastructure adapters.

**Non-Goals:**

- Implement Customer registration, password recovery, email verification, MFA, or Keycloak user provisioning.
- Expose OAuth tokens to the SPA or support direct email/password submission to PayCore.
- Build a general authorization/roles model or secure module-specific business operations beyond establishing the authenticated Customer.
- Add Redis, a custom encrypted token vault, device management UI, or implicit Keycloak SSO logout.
- Define native-mobile or third-party API authentication.

## Decisions

### Use a same-site BFF with a confidential OIDC client

The SPA initiates login through PayCore. PayCore acts as a confidential OAuth2 client, generates state, nonce, and PKCE values, receives the authorization callback, and exchanges the code with Keycloak over the back channel. The SPA and BFF are exposed under one site so the browser session does not depend on third-party cookies or cross-site credentialed CORS.

Keycloak owns passwords, password hashing policy, brute-force controls, recovery, and future MFA. PayCore never receives a Customer password. Non-secret realm/client settings are versioned reproducibly; client credentials are supplied through deployment secrets.

Alternative considered: a SPA OAuth client that stores bearer tokens. Rejected because browser JavaScript would handle access and refresh tokens. Alternative considered: a direct-grant login endpoint. Rejected because PayCore would handle credentials and the flow is discouraged by modern OAuth guidance.

### Store an opaque session cookie and keep tokens server-side

The browser receives `__Host-paycore-session` containing only a high-entropy session identifier. It is configured with `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, and no `Domain`. Authentication rotates the session identifier to prevent session fixation.

Spring Security's `SecurityContext` and the `OAuth2AuthorizedClient` containing access and refresh tokens live in `HttpSession`. The browser never receives either token. Token refresh occurs through a server-side authorized-client manager; failed or revoked refresh invalidates the local session.

Alternative considered: encrypted, self-contained session cookies. Rejected because they increase cookie size and make immediate revocation and token rotation harder. Alternative considered: a separate encrypted token vault. Deferred until production requirements justify a KMS, key rotation, and additional persistence code.

### Persist HttpSession in PostgreSQL through Spring Session JDBC

Spring Session JDBC replaces container-local session storage. Its schema is installed by a versioned Flyway migration rather than Spring's automatic schema initializer. PostgreSQL is already required by PayCore, so this provides restart survival and horizontal-instance compatibility without adding Redis.

Session records are indexed by the stable local Customer principal so infrastructure can enumerate and revoke every session for a Customer. Database access is least-privileged; logs, metrics, and error payloads must never contain cookies or OAuth tokens. Production storage and backups must provide encryption at rest.

Alternative considered: in-memory HttpSession. Allowed only for isolated local development because sessions disappear on restart and cannot be shared. Alternative considered: Redis. Deferred until measured load or operational requirements justify another stateful dependency.

### Resolve issuer and subject through an application use case

The OIDC adapter extracts only validated `iss` and `sub` claims and calls an application input port such as `ResolveCustomerAccess`. The application uses a persistence output port to resolve `(issuer, subject)` to a stable `CustomerId` and current `CustomerStatus`. Mutable claims such as email are not identity keys.

The application result contains framework-free identity data. Infrastructure maps that result to Spring's authenticated principal. Persistence entities and transport/security objects are mapped at adapter boundaries and are never exposed as domain objects.

Conceptual dependency flow:

```text
infrastructure/security (Spring, OIDC, session)
                 |
                 v
application/usecase (resolve Customer access)
                 |
                 v
domain (CustomerId, ExternalIdentity, CustomerStatus)
```

Meaningful application output ports are limited to:

- resolving the external identity and Customer status;
- revoking one or all Customer sessions when a use case requires it.

There is no domain-level `JwtService`, `PasswordEncoder`, or Keycloak interface. Those concerns are owned by the security adapter. The Keycloak Admin API needed by registration is a separate outbound adapter and is not part of authentication.

### Revalidate Customer status on every protected request

The local `CustomerId` is stored as the authenticated principal, but active status is loaded through the application boundary for each protected request. This makes suspension effective without waiting for the eight-hour session lifetime. When an inactive Customer is observed, the current request is denied and every indexed session for that Customer is removed.

The Customer-status transition use case must also call the session-revocation output port so suspension proactively closes sessions. Per-request validation remains a defense against missed or delayed revocation.

Alternative considered: cache status in the session. Rejected initially because it creates a period in which a suspended Customer remains authenticated. A short cache may be introduced later only with explicit invalidation and measured need.

### Apply independent idle and absolute session limits

Spring Session's inactivity timeout is 30 minutes. An absolute expiration instant, computed from an injected `Clock`, is recorded only after successful authentication and enforced before protected processing. It is fixed at eight hours and is not extended by request activity or OAuth token refresh. All instants use UTC in accordance with ADR-0004.

Before each session save, the remaining inactivity interval is capped at the smaller of 30 minutes and the time remaining until absolute expiration. Therefore the backing session repository cannot consider a session active beyond the eight-hour deadline even if no later protected request arrives. Expired-row cleanup removes the persisted attributes and tokens; cleanup timing is monitored and tested separately from access denial.

Multiple sessions are allowed. Each browser/device receives an independent session, expiry timeline, and OAuth authorized client. A normal CSRF-protected logout invalidates only the current session and clears its cookie and tokens. A separate future operation may close all sessions; normal logout does not terminate Keycloak SSO.

### Keep CSRF enabled for cookie-authenticated requests

Because browsers attach the session cookie automatically, all unsafe HTTP methods require a CSRF token associated with the session. The SPA obtains the token through the BFF and returns it in a request header. `SameSite=Lax`, origin checks, and restrictive same-site deployment are defense in depth, not replacements for CSRF validation. Logout uses an unsafe method and requires CSRF protection.

### Separate authentication and access-denied failures

Missing, invalid, or expired local sessions return HTTP 401 with a generic response. Valid external identities that do not map to an active Customer fail login with HTTP 403 without revealing whether the link or status caused the denial. A protected request that first detects an inactive Customer returns HTTP 403 while revoking all sessions; reuse of those sessions returns HTTP 401. Missing or invalid CSRF tokens return HTTP 403. Internal OIDC, token, claim, and Keycloak details are logged only in sanitized operational events and are never returned to the browser.

## Risks / Trade-offs

- [Keycloak becomes critical infrastructure] -> Use health monitoring, TLS, backups, reproducible realm/client configuration, and explicit availability objectives.
- [PostgreSQL session rows contain serialized security state and OAuth tokens] -> Restrict database privileges, encrypt storage/backups, never log attributes, keep session lifetimes bounded, and evaluate an encrypted token vault before higher-risk production use.
- [Session serialization can break across deployments] -> Keep session attribute types controlled, test upgrade compatibility, and accept planned session invalidation when an incompatible deployment is unavoidable.
- [A database lookup occurs on every protected request] -> Start with correctness; measure before adding a short-lived cache with explicit invalidation.
- [PostgreSQL session cleanup can accumulate expired rows] -> Configure and monitor cleanup, index expiry/principal columns, and test cleanup against PostgreSQL.
- [OIDC key or client-secret rotation can interrupt login] -> Use issuer discovery/JWKS caching, support overlapping signing keys, externalize client secrets, and exercise rotation in contract tests.
- [Session theft remains possible through browser or transport compromise] -> Require HTTPS, secure cookie attributes, session-id rotation, CSRF protection, bounded lifetimes, and prompt revocation.
- [Customer registration and Keycloak provisioning are not atomic] -> Let `register-customer` own idempotency, compensation, and reconciliation; authentication treats missing linkage as access denied.

## Migration Plan

1. Add the required Spring OAuth2 Client and Spring Session JDBC dependencies and secure configuration properties.
2. Provision a Keycloak realm/client configured for confidential Authorization Code flow with PKCE and allow only the PayCore callback/logout origins.
3. Keep the existing V1 external identity linkage unchanged and apply a new Flyway migration for Spring Session JDBC tables before enabling BFF login traffic.
4. Deploy PayCore with authentication endpoints disabled until issuer, client credentials, HTTPS, and same-site browser routing are available.
5. Enable login and monitor login failures, refresh failures, session counts, cleanup, and Customer-access denials without recording credentials or tokens.

Rollback disables browser login and returns the application to the previous unauthenticated state. Newly created session/link tables can remain unused; no released migration is edited or removed. Existing BFF sessions are invalidated during rollback.

## Resolved Questions

- Keycloak 26.5.2 is pinned for local and contract testing, matching the registration capability's reproducible realm configuration.
