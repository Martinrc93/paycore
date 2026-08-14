## MODIFIED Requirements

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

## ADDED Requirements

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
