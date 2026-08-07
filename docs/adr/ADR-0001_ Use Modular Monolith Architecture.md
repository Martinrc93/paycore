# ADR-0001: Use Modular Monolith Architecture

## Status

Accepted

## Date

2026-08-07

## Context

PayCore is a payment and ledger platform that will contain multiple business capabilities, including:

- Identity
- Accounts
- Ledger
- Transfers
- Payments
- Refunds
- Risk
- Reconciliation
- Audit

These capabilities require clear boundaries because some of them may evolve independently in the future.

A microservices architecture was considered, but PayCore is currently being developed as a new system without demonstrated requirements for independent deployment or horizontal scaling of individual modules.

Starting with microservices would introduce additional operational complexity, including:

- distributed communication;
- service discovery;
- network failures;
- distributed tracing;
- distributed transactions;
- multiple deployment pipelines;
- additional infrastructure;
- increased local development complexity.

At the same time, a traditional unstructured monolith could create excessive coupling between PayCore's business domains.

## Decision

PayCore will start as a **Modular Monolith**.

The application will be deployed as a single Spring Boot application, while the internal codebase will be divided into explicit business modules.

Initial modules include:

```text
identity
account
ledger
transfer
payment
refund
risk
reconciliation
audit
```

Each module must expose a deliberate public boundary and must avoid accessing the internal implementation of other modules directly.

Modules should communicate through explicit application interfaces or domain/application events where appropriate.

## Alternatives Considered

### Microservices

Each business capability could be deployed as an independent service.

This option was rejected for the initial version because the operational and distributed-system complexity is not justified by current requirements.

Microservices may be reconsidered if PayCore later requires independent deployment, scaling, ownership, or isolation of specific capabilities.

### Traditional Monolith

All application components could exist without strict module boundaries.

This option was rejected because it would make coupling between domains easier and future architectural evolution more difficult.

## Consequences

### Positive

- Simpler deployment.
- Simpler local development.
- Easier transactional consistency.
- Lower infrastructure complexity.
- Clear business boundaries.
- Easier refactoring while the domain is still evolving.
- Possibility of extracting modules into services in the future.

### Negative

- All modules are deployed together.
- One module cannot be independently scaled.
- Module boundaries must be enforced through conventions and architecture tests.
- Poor discipline could turn the modular monolith into a tightly coupled monolith.

## Notes

This decision does not prohibit future microservices.

A module should only be extracted when there is a demonstrated technical or organizational reason to do so.