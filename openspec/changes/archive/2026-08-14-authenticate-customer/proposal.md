## Why

PayCore needs a secure way for a registered Customer to authenticate from a browser without exposing identity-provider tokens or passwords to the SPA. Establishing this boundary now also defines how the first `identity` module integrates with Keycloak while preserving the modular-monolith and hexagonal architecture constraints.

## What Changes

- Add browser authentication through a PayCore backend-for-frontend (BFF) and Keycloak using OpenID Connect Authorization Code flow with PKCE.
- Keep credentials in Keycloak and keep OAuth access and refresh tokens exclusively in a server-side PayCore session.
- Issue the browser an opaque, secure, HTTP-only session cookie instead of exposing bearer tokens.
- Persist local sessions in PostgreSQL through Spring Session JDBC, with 30-minute idle and 8-hour absolute expiration.
- Resolve the external identity to an active local Customer on protected requests and revoke all local sessions when that Customer is suspended.
- Allow multiple independent sessions per Customer and make normal logout invalidate only the current session.
- Protect state-changing BFF requests against CSRF and return consistent authentication and authorization errors.

## Capabilities

### New Capabilities

- `identity/customer-authentication`: Browser login, local Customer resolution, BFF session lifecycle, protected-request authentication, and logout behavior.

### Modified Capabilities

None.

## Impact

- Depends on the completed `register-customer` OpenSpec change, which owns the Customer model and stable external identity linkage contract.
- Extends the existing `identity` module boundaries across domain, application, and infrastructure packages.
- Adds Spring Security OAuth2 Client and Spring Session JDBC capabilities managed by Spring Boot 4.1.0.
- Integrates PayCore with a self-hosted Keycloak deployment and its OIDC issuer endpoints.
- Adds PostgreSQL tables through a new Flyway migration for Spring-backed sessions and external identity linkage.
- Adds browser-facing login, CSRF, and logout behavior at the BFF boundary.
- Requires Keycloak realm/client configuration to be reproducible and covered by integration or contract tests.
