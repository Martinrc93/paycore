## Why

PayCore needs a durable, non-enumerating way to register an authentication-ready Customer across PostgreSQL and Keycloak before Customer authentication can be implemented. The registration boundary must remain recoverable when either system fails because they cannot share an atomic transaction.

## What Changes

- Add public Customer registration with an email address, Customer type (`INDIVIDUAL` or `BUSINESS`), and a mandatory idempotency key.
- Return the same generic accepted response for new and already-registered emails to avoid account enumeration.
- Create a local Customer intent and process Keycloak provisioning through a durable asynchronous saga with explicit lifecycle states, PostgreSQL-backed work claiming, and retry scheduling.
- Provision a Keycloak identity without receiving a password and request email verification plus password creation through Keycloak required actions.
- Persist the stable `(issuer, subject)` external identity link required by Customer authentication.
- Recover safely from concurrent workers, retries, expired claims, and partial failures without automatically linking an unrelated Keycloak user that merely shares an email.
- Defer complete individual and business profiles, KYC, and document verification to separate capabilities.

## Capabilities

### New Capabilities

- `identity/customer-registration`: Idempotent Customer registration, Keycloak identity provisioning, lifecycle transitions, duplicate-email privacy, and partial-failure recovery.

### Modified Capabilities

None.

## Impact

- Introduces the core Customer aggregate and public application boundary in the `identity` module.
- Adds a public registration endpoint and transport validation.
- Adds PostgreSQL tables and constraints through Flyway for Customers, external identity links, registration idempotency, provisioning state, worker leases, and retry scheduling.
- Adds a Keycloak Admin API adapter using a least-privileged service account.
- Establishes the Customer and external identity contract required by the separate `authenticate-customer` change.
- Adds an in-process provisioning worker coordinated through PostgreSQL without introducing a broker.
- Requires PostgreSQL worker/concurrency tests and Keycloak contract/integration tests.
