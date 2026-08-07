# PayCore Agent Instructions

## Current State

- PayCore is an early payment and double-entry ledger scaffold; do not assume the target modules or ledger behavior exist yet.
- The executable stack is Java 21, Spring Boot 4.1.0, Maven Wrapper 3.3.4 running Maven 3.9.16, PostgreSQL, and Flyway.
- `openspec/specs/` and active `openspec/changes/` are currently empty. Check them again before every non-trivial change.

## Sources of Truth

- OpenSpec is the source of truth for functional behavior; `pom.xml` is the source of truth for dependency versions and available libraries.
- Before non-trivial implementation, read the relevant specs, active change, related ADRs when present, tests, and implementation, in that order.
- Do not invent requirements. If a prompt is vague or introduces new behavior, explore and create or update an OpenSpec change before writing production code.
- Engram records durable project knowledge, but never overrides OpenSpec. GitHub issues and PRs also do not replace specifications.

## Workflow

- Use the repository's OpenSpec commands/skills for proposal, apply, sync, and archive work.
- Use relevant Superpowers skills before acting: brainstorming for uncertain designs, TDD for implementation, systematic debugging for failures, code review before completion, and verification before claiming success.
- Keep OpenSpec tasks synchronized with implementation and archive a change only after its requirements are verified.
- Use Context7 for current framework or library APIs, selecting documentation compatible with the versions in `pom.xml`, especially Spring Boot 4.1.0.

## Target Architecture

These are constraints for new code, not claims about the current scaffold:

- Build a domain-oriented modular monolith using hexagonal architecture.
- Dependencies point inward: infrastructure -> application -> domain.
- Domain code must not depend on Spring, JPA, HTTP, messaging, caches, or persistence entities.
- Keep persistence models and transport DTOs outside the domain; map them at adapter boundaries.
- Do not introduce cross-module access that bypasses an owning module's public application API.

## Financial Invariants

These are mandatory constraints for future financial behavior, not claims about implemented ledger functionality:

- Never represent money with `float` or `double`; use `BigDecimal` with an explicit currency.
- Ledger entries are immutable. Never update or delete confirmed entries; correct them with compensating transactions.
- Every financial transaction must satisfy `SUM(DEBITS) == SUM(CREDITS)` and commit atomically.
- Design externally retryable financial operations for idempotency and test concurrent execution where balances or limits can race.

## Database

- Every schema change requires a new Flyway migration.
- Never edit a migration that has already been released or applied to a shared environment.

## Commands

Use the wrapper rather than a globally installed Maven:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Dtest=PaycoreApplicationTests test
.\mvnw.cmd -Dtest=PaycoreApplicationTests#contextLoads test
```

```bash
./mvnw test
./mvnw -Dtest=PaycoreApplicationTests test
./mvnw -Dtest=PaycoreApplicationTests#contextLoads test
```

## Testing and Completion

- Financial behavior must cover the happy path, insufficient funds, invalid state, rollback, concurrency, and idempotency when applicable.
- Use integration tests for PostgreSQL, Flyway, transaction, locking, and adapter behavior; do not replace infrastructure semantics with mocks.
- Known baseline on 2026-08-07: `mvnw test` fails because `PaycoreApplicationTests` starts JPA/Flyway without a datasource URL or embedded database. Do not attribute this failure to unrelated work or hide it; establish the intended database-backed test setup before relying on the context-load test.
- Before completion, run focused affected tests and the full `mvnw test` suite. Report any remaining failure exactly; never claim tests pass when they do not.
- Confirm OpenSpec requirements and tasks are satisfied, architecture boundaries still hold, and documentation is updated when behavior or operation changed.
