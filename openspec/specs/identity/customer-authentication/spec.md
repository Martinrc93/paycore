# identity/customer-authentication Specification

## Purpose

Defines secure browser authentication for registered Customers while keeping credentials and identity-provider tokens outside the browser and enforcing local Customer access state.

## Requirements

### Requirement: Browser authentication uses the external identity provider
The system SHALL authenticate browser users through the configured OpenID Connect identity provider using Authorization Code flow with PKCE. PayCore SHALL NOT receive or store the Customer's plaintext password.

#### Scenario: Customer starts login
- **WHEN** an unauthenticated browser starts Customer login
- **THEN** the system redirects the browser to the configured identity provider with an authorization request protected by state, nonce, and PKCE

#### Scenario: Identity provider rejects credentials
- **WHEN** the identity provider rejects the Customer's credentials
- **THEN** PayCore does not create an authenticated local session and does not learn whether the submitted email or password was incorrect

### Requirement: Successful login creates an opaque browser session
After successful external authentication, the system SHALL create a server-side session only after resolving the linked Customer and confirming or establishing its active status. A `PENDING_VERIFICATION` Customer SHALL become `ACTIVE` only when the validated OIDC identity carries `email_verified=true`. The session SHALL retain the authenticated Customer context and associated OAuth access and refresh tokens. The system SHALL send the browser only an opaque session identifier in a cookie named `__Host-paycore-session`. The cookie SHALL be Secure, HttpOnly, use SameSite=Lax, use Path=/, and omit the Domain attribute. OAuth access and refresh tokens SHALL NOT be returned to or stored by the browser.

#### Scenario: Active registered Customer completes login
- **WHEN** the identity provider authenticates an external identity linked to an active Customer
- **THEN** the system creates a local session and returns the opaque session cookie without exposing OAuth tokens

#### Scenario: Pending Customer completes verified login
- **WHEN** the identity provider authenticates a linked `PENDING_VERIFICATION` Customer and the validated OIDC identity carries `email_verified=true`
- **THEN** the system atomically activates the Customer before creating the local session and returning the opaque session cookie

#### Scenario: Pending Customer login lacks verified-email evidence
- **WHEN** the identity provider authenticates a linked `PENDING_VERIFICATION` Customer and the `email_verified` claim is false or absent
- **THEN** the system leaves the Customer pending, creates no local session, and returns a sanitized access denial

#### Scenario: Concurrent verified logins activate once
- **WHEN** concurrent successful OIDC callbacks resolve the same linked `PENDING_VERIFICATION` Customer with `email_verified=true`
- **THEN** exactly one activation transition commits and every successful callback observes the same active Customer

#### Scenario: Session identifier is renewed after login
- **WHEN** an unauthenticated browser becomes authenticated
- **THEN** the system changes the browser session identifier before accepting authenticated requests

### Requirement: External identity resolves to a local Customer
The system SHALL resolve an authenticated external identity by the combination of issuer and subject. Email and other mutable claims SHALL NOT be used as the stable identity key. The standard OIDC `email_verified` claim from the validated linked identity SHALL be used only as evidence for the pending-to-active transition and SHALL NOT replace issuer and subject linkage.

#### Scenario: Linked active Customer is resolved
- **WHEN** a valid external identity has a link to an active Customer
- **THEN** the authenticated session is associated with that Customer's stable local identifier

#### Scenario: Linked pending Customer presents verified evidence
- **WHEN** a valid external identity has a link to a `PENDING_VERIFICATION` Customer and carries `email_verified=true`
- **THEN** the system activates and resolves that same Customer by issuer and subject rather than by email

#### Scenario: External identity is not linked
- **WHEN** a valid external identity has no local Customer link
- **THEN** the system refuses to create an authenticated session and responds with HTTP 403 without revealing whether a Customer record exists

#### Scenario: Linked Customer is not active
- **WHEN** a valid external identity is linked to a suspended, blocked, or otherwise inactive Customer that is not eligible for verified activation
- **THEN** the system refuses to create an authenticated session and responds with HTTP 403 without revealing the Customer's status

### Requirement: Existing active Customers are revalidated
The system SHALL migrate every existing `ACTIVE` Customer to `PENDING_VERIFICATION` and SHALL revoke that Customer's existing local sessions during rollout. Suspended, blocked, provisioning, and provisioning-failed Customers SHALL retain their current status. A migrated Customer SHALL regain access only through a subsequent linked OIDC login carrying `email_verified=true`.

#### Scenario: Existing active Customer is migrated
- **WHEN** the verified-activation migration is applied to an `ACTIVE` Customer
- **THEN** the Customer becomes `PENDING_VERIFICATION` and all existing local sessions for that Customer are invalidated

#### Scenario: Existing inactive Customer is migrated
- **WHEN** the migration encounters a suspended, blocked, provisioning, or provisioning-failed Customer
- **THEN** the Customer's status remains unchanged

#### Scenario: Migrated Customer logs in with verified evidence
- **WHEN** a migrated `PENDING_VERIFICATION` Customer completes a linked OIDC login with `email_verified=true`
- **THEN** the Customer becomes `ACTIVE` and receives a new local session

#### Scenario: Revoked pre-migration session is reused
- **WHEN** a session invalidated by the migration is presented after rollout
- **THEN** the system treats the request as unauthenticated

### Requirement: Protected requests enforce current Customer status
The system SHALL verify that the session's Customer remains active before permitting each protected request.

#### Scenario: Active Customer uses a valid session
- **WHEN** a protected request carries a valid unexpired session for an active Customer
- **THEN** the system authenticates the request as that Customer

#### Scenario: Customer became inactive
- **WHEN** a protected request carries a session for a Customer who is no longer active
- **THEN** the system responds with HTTP 403 and invalidates all local sessions belonging to that Customer

#### Scenario: Revoked Customer session is reused
- **WHEN** a request presents a session invalidated because its Customer became inactive
- **THEN** the system responds with HTTP 401

#### Scenario: Session is absent or invalid
- **WHEN** a protected request has no valid local session
- **THEN** the system responds with HTTP 401 without exposing identity-provider or cryptographic details

### Requirement: Local sessions have idle and absolute expiration
An authenticated session SHALL expire after 30 minutes without activity and SHALL expire no later than 8 hours after successful authentication regardless of activity. Token renewal SHALL NOT extend the session beyond its absolute expiration.

#### Scenario: Session exceeds idle timeout
- **WHEN** 30 minutes pass without activity in an authenticated session
- **THEN** the next protected request is unauthenticated and the expired session cannot be reused

#### Scenario: Session reaches absolute lifetime
- **WHEN** 8 hours pass after successful authentication despite continued activity
- **THEN** the next protected request is unauthenticated and a new login is required

#### Scenario: Access token expires during a valid local session
- **WHEN** the identity-provider access token expires while the local session and server-side refresh token remain valid
- **THEN** the system renews the access token server-side without exposing either token to the browser or extending the absolute session lifetime

#### Scenario: Identity provider rejects token renewal
- **WHEN** the identity provider rejects or revokes the server-side refresh token
- **THEN** the system invalidates the local session and requires a new login

### Requirement: A Customer can hold multiple independent sessions
The system SHALL permit a Customer to have multiple concurrent sessions and SHALL maintain each session independently.

#### Scenario: Customer logs in from two devices
- **WHEN** the same active Customer completes login from two browsers or devices
- **THEN** the system maintains two independently expiring sessions

#### Scenario: Customer is suspended with multiple sessions
- **WHEN** a Customer with multiple active sessions becomes suspended or blocked
- **THEN** the system invalidates every local session belonging to that Customer

### Requirement: State-changing browser requests require CSRF protection
The system SHALL require a valid CSRF token for authenticated state-changing browser requests and SHALL NOT rely on the SameSite cookie attribute as the sole CSRF defense.

#### Scenario: State-changing request has a valid CSRF token
- **WHEN** an authenticated browser submits a state-changing request with the valid CSRF token associated with its session
- **THEN** the system evaluates the request normally

#### Scenario: CSRF token is missing or invalid
- **WHEN** an authenticated browser submits a state-changing request without a valid CSRF token
- **THEN** the system responds with HTTP 403 and performs no requested state change

### Requirement: Logout invalidates only the current session
Normal Customer logout SHALL invalidate the current local session, remove its server-side OAuth tokens, and expire its browser cookie. It SHALL NOT invalidate the Customer's other local sessions or implicitly terminate the identity-provider SSO session.

#### Scenario: Customer logs out from one device
- **WHEN** an authenticated Customer submits a logout request with valid CSRF protection
- **THEN** the system invalidates that device's session and leaves the Customer's other sessions active

#### Scenario: Logout request is replayed
- **WHEN** a previously logged-out session identifier is presented again
- **THEN** the system treats the request as unauthenticated
