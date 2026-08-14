## Why

PayCore currently marks a Customer `ACTIVE` after Keycloak provisioning and required-action email dispatch, before the Customer has verified the email address. The wallet architecture requires `ACTIVE` to mean that PayCore has observed trusted email-verification evidence, so identity semantics must be corrected before wallet provisioning is introduced.

## What Changes

- **BREAKING** Replace post-provisioning activation with a `PENDING_VERIFICATION` state that remains unavailable for normal authenticated access.
- Transition a linked Customer to `ACTIVE` during the first successful OIDC login that carries trusted `email_verified=true` evidence.
- Reject a pending Customer's login without creating a local session when the verification claim is false or absent, while preserving sanitized non-enumerating failure behavior.
- Make concurrent and repeated verified-login activation idempotent so exactly one state transition commits and successful callers observe an active Customer.
- **BREAKING** Migrate existing `ACTIVE` Customers to `PENDING_VERIFICATION`, revoke their existing sessions, and require verified OIDC reauthentication before restoring access.
- Preserve provisioning recovery, stable `(issuer, subject)` identity linkage, session security, and suspended/blocked Customer semantics.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `identity/customer-registration`: Provisioning completion now leaves a Customer pending email verification instead of activating it.
- `identity/customer-authentication`: A pending Customer becomes active only during a linked OIDC login with trusted verified-email evidence; unverified login is denied without creating a local session.

## Impact

- Customer lifecycle domain states and transitions.
- Registration worker completion and persistence behavior.
- OIDC callback/access-resolution orchestration and claim validation.
- PostgreSQL Customer status constraint and data migration.
- Existing server-side session revocation during rollout.
- Registration, authentication, persistence, security, concurrency, migration, and Keycloak contract tests.
- Customer registration and authentication runbooks.
