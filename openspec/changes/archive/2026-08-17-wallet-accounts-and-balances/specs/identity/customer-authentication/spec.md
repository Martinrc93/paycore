## MODIFIED Requirements

### Requirement: Successful login creates an opaque browser session
After successful external authentication, the system SHALL resolve the linked Customer and confirm or establish its active status before creating a local session. A `PENDING_VERIFICATION` Customer SHALL become `ACTIVE` only when the validated OIDC identity carries `email_verified=true`; that activation SHALL atomically provision exactly one USD wallet with its available and reserved ledger accounts. No Customer SHALL become or remain admitted as `ACTIVE` without a completely provisioned wallet. If activation or wallet provisioning fails, none of those local effects SHALL commit and the system SHALL create no local session. The session SHALL retain the authenticated Customer context and associated OAuth access and refresh tokens. The system SHALL send the browser only an opaque session identifier in a cookie named `__Host-paycore-session`. The cookie SHALL be Secure, HttpOnly, use SameSite=Lax, use Path=/, and omit the Domain attribute. OAuth access and refresh tokens SHALL NOT be returned to or stored by the browser.

#### Scenario: Active registered Customer completes login
- **WHEN** the identity provider authenticates an external identity linked to an active Customer with a completely provisioned wallet
- **THEN** the system creates a local session and returns the opaque session cookie without exposing OAuth tokens

#### Scenario: Pending Customer completes verified login
- **WHEN** the identity provider authenticates a linked `PENDING_VERIFICATION` Customer and the validated OIDC identity carries `email_verified=true`
- **THEN** the system atomically activates the Customer, provisions exactly one USD wallet and its two ledger accounts, creates the local session, and returns the opaque session cookie

#### Scenario: Pending Customer login lacks verified-email evidence
- **WHEN** the identity provider authenticates a linked `PENDING_VERIFICATION` Customer and the `email_verified` claim is false or absent
- **THEN** the system leaves the Customer pending, creates no wallet or local session, and returns a sanitized access denial

#### Scenario: Wallet provisioning fails during verified activation
- **WHEN** a linked pending Customer presents verified evidence but wallet or ledger-account provisioning cannot commit
- **THEN** the system rolls back the activation and all wallet/account effects, creates no local session, and permits a later verified login to retry

#### Scenario: Active Customer has no complete wallet
- **WHEN** an authenticated identity resolves to an `ACTIVE` Customer without a completely provisioned wallet
- **THEN** the system does not create a local session or treat the Customer as eligible for active access, and reports a sanitized activation failure

#### Scenario: Concurrent verified logins activate once
- **WHEN** concurrent successful OIDC callbacks resolve the same linked `PENDING_VERIFICATION` Customer with `email_verified=true`
- **THEN** exactly one activation and wallet provisioning transition commits, every successful callback observes the same active Customer and wallet, and no orphan wallet accounts are created

#### Scenario: Session identifier is renewed after login
- **WHEN** an unauthenticated browser becomes authenticated
- **THEN** the system changes the browser session identifier before accepting authenticated requests

### Requirement: Existing active Customers are revalidated
The coordinated rollout SHALL first apply V4/revalidation, which migrates every existing `ACTIVE` Customer to `PENDING_VERIFICATION` and revokes that Customer's existing local sessions. Suspended, blocked, provisioning, and provisioning-failed Customers SHALL retain their current status. Only after V4 completes, V5 SHALL backfill exactly one completely provisioned `UNFUNDED` USD wallet and its two USD non-negative liability accounts for Customers that are `ACTIVE` during the controlled backfill interval. V5 SHALL enforce that every `ACTIVE` Customer has a complete wallet and SHALL be safe to rerun without duplicates. A migrated Customer SHALL regain access only through a subsequent linked OIDC login carrying `email_verified=true`, which provisions the wallet before accepting the new active session.

#### Scenario: Existing active Customer is migrated
- **WHEN** V4/revalidation is applied to an `ACTIVE` Customer before V5 wallet backfill
- **THEN** the Customer becomes `PENDING_VERIFICATION` and all existing local sessions for that Customer are invalidated

#### Scenario: Existing inactive Customer is migrated
- **WHEN** the migration encounters a suspended, blocked, provisioning, or provisioning-failed Customer
- **THEN** the Customer's status remains unchanged

#### Scenario: Active Customer is backfilled with one wallet
- **WHEN** V5 wallet backfill processes an `ACTIVE` Customer without a wallet during the controlled interval after V4
- **THEN** the system creates one `UNFUNDED` USD wallet with available and reserved USD non-negative liability accounts and associates all three records with that Customer

#### Scenario: V5 enforces the active-Customer wallet invariant
- **WHEN** V5 backfill validation completes
- **THEN** every `ACTIVE` Customer has exactly one completely provisioned USD wallet, and any Customer lacking one is not admitted as active

#### Scenario: Wallet backfill is repeated
- **WHEN** the wallet backfill is rerun for a Customer already owning a completely provisioned wallet
- **THEN** the system preserves the existing wallet and accounts without creating duplicates or changing Customer access status

#### Scenario: Migrated Customer logs in with verified evidence
- **WHEN** a migrated `PENDING_VERIFICATION` Customer completes a linked OIDC login with `email_verified=true`
- **THEN** the Customer becomes `ACTIVE`, receives exactly one completely provisioned wallet, and receives a new local session

#### Scenario: Revoked pre-migration session is reused
- **WHEN** a session invalidated by the migration is presented after rollout
- **THEN** the system treats the request as unauthenticated

## ADDED Requirements

### Requirement: Wallet provisioning is isolated behind the identity activation boundary
The identity module SHALL request wallet provisioning and activation through an explicit application port. It SHALL NOT access wallet persistence entities, ledger persistence entities, or database tables directly, and a failed wallet operation SHALL be reported as a failed activation rather than an accepted authenticated session.

#### Scenario: Verified activation delegates through the application port
- **WHEN** the identity authentication flow activates a pending Customer
- **THEN** it invokes the published wallet-provisioning boundary and creates no persistence coupling to wallet or ledger infrastructure

#### Scenario: Wallet boundary rejects activation
- **WHEN** the wallet-provisioning boundary cannot complete atomically
- **THEN** the identity flow denies access without creating a local session or committing a partial Customer activation
