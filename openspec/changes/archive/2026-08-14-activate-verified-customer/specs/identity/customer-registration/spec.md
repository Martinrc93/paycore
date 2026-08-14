## MODIFIED Requirements

### Requirement: Customer activation requires complete identity provisioning
The system SHALL keep a new Customer unavailable for authentication while provisioning is incomplete. After the external identity exists, the stable external identity link is persisted, and the external provider accepts the request to deliver email verification and password-creation actions, the system SHALL mark the Customer `PENDING_VERIFICATION` rather than `ACTIVE`. The Customer SHALL become `ACTIVE` only after customer authentication observes trusted verified-email evidence for the linked external identity.

#### Scenario: Provisioning completes
- **WHEN** all external identity provisioning steps and local persistence steps complete
- **THEN** the Customer becomes `PENDING_VERIFICATION` and remains unavailable for normal authenticated access

#### Scenario: Provisioning is incomplete
- **WHEN** any required provisioning step has not completed
- **THEN** the Customer remains unavailable for authentication

#### Scenario: Verification has not been observed
- **WHEN** provisioning completed but PayCore has not observed trusted verified-email evidence during linked Customer authentication
- **THEN** the Customer remains `PENDING_VERIFICATION` and does not become `ACTIVE`
