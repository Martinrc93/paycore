# ADR-0002: Use Hexagonal Architecture

## Status

Accepted

## Date

2026-08-07

## Context

PayCore contains business rules with high consistency requirements, particularly around:

- money;
- accounts;
- transfers;
- ledger entries;
- payments;
- refunds.

These business rules should not depend directly on frameworks or infrastructure technologies such as:

- Spring Boot;
- Spring Data JPA;
- PostgreSQL;
- Kafka;
- Redis;
- HTTP.

Coupling the domain directly to these technologies would make business rules harder to test, maintain and evolve.

## Decision

PayCore will use **Hexagonal Architecture** inside its business modules.

Each module will generally be structured around:

```text
module/
│
├── domain/
│
├── application/
│   ├── usecase/
│   └── port/
│
└── infrastructure/
    ├── persistence/
    ├── web/
    └── messaging/
```

Dependencies must point toward the business logic.

Conceptually:

```text
Infrastructure
      ↓
Application
      ↓
Domain
```

The domain layer must not depend on infrastructure.

## Dependency Rules

The domain must not depend on:

- Spring;
- JPA;
- HTTP;
- Kafka;
- Redis;
- PostgreSQL-specific APIs.

Persistence entities must not be exposed as domain objects.

Repository contracts required by business logic must be defined as ports and implemented by infrastructure adapters.

External systems must be accessed through explicit ports.

## Example

The transfer application logic may depend on:

```text
AccountRepository
LedgerRepository
TransactionRepository
```

as application/domain ports.

Infrastructure may provide implementations such as:

```text
JpaAccountRepositoryAdapter
JpaLedgerRepositoryAdapter
```

The use case does not need to know that PostgreSQL or JPA is being used.

## Alternatives Considered

### Traditional Layered Architecture

Controller → Service → Repository → Database.

This architecture is simpler initially but frequently allows persistence and framework concerns to leak into business logic.

It was rejected as the primary architectural model because PayCore's domain rules are expected to become sufficiently complex to benefit from stronger boundaries.

### Clean Architecture

Clean Architecture provides similar dependency principles.

It was considered valid, but Hexagonal Architecture was selected because ports and adapters provide a direct model for PayCore's interaction with databases, APIs and future messaging infrastructure.

## Consequences

### Positive

- Business rules remain independent from frameworks.
- Domain logic can be unit tested without Spring.
- Infrastructure can evolve independently.
- External integrations are explicit.
- Module boundaries become easier to understand.
- Replacing adapters requires fewer changes to business logic.

### Negative

- More interfaces and mapping code.
- Higher initial complexity than a basic layered architecture.
- Developers must understand ports and adapters.
- Poorly designed abstractions can create unnecessary indirection.

## Notes

Hexagonal Architecture should not be applied mechanically.

Ports should exist when they represent meaningful boundaries.

Interfaces must not be created only for the purpose of having an interface.