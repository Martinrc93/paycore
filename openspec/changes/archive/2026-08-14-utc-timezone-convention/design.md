## Context

PayCore is a greenfield Spring Boot 4.1.0 / Java 21 scaffold with no entities, no Flyway migrations, no DTOs, and no API endpoints. There is currently no date/time handling anywhere in the codebase, so this design establishes the convention from scratch rather than migrating existing behavior. See proposal.md - Why for motivation.

## Goals / Non-Goals

**Goals:**

- Make UTC deterministic across the whole stack: JVM default, Jackson, Hibernate/JDBC, PostgreSQL, tests, and CI.
- Define storage and serialization semantics that produce an unambiguous, host-independent contract.
- Document the Daylight Saving Time rule for future local-time scheduling so it survives into features that do not exist yet.

**Non-Goals:**

- No data migration: there are zero columns and zero rows today.
- No domain entities, Flyway migrations, or API endpoints: those belong to future changes.
- No support for scheduling features yet; only the rule that governs them when they arrive.

## Decisions

- **JVM default timezone via runtime + deployment environment.** `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))` is set as the first statement of `PaycoreApplication.main()` as a belt-and-suspenders measure. The single source of truth for production will be the container/deployment environment (`TZ=UTC`, plus `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`), to be applied in the future Dockerfile. Rationale: mutating the JVM default at runtime is fragile (anything using the default during class loading runs first), so it is defense, not the primary mechanism. No Dockerfile exists yet; the rule is recorded in the ADR.
- **Tests run with `user.timezone=UTC` via Surefire.** Maven Surefire forks a separate JVM, so `.mvn/jvm.config` (which only affects the Maven JVM) is insufficient. `spring-boot-starter-parent` already manages the Surefire plugin; the change overrides it with a `systemPropertyVariables` entry for `user.timezone`.
- **`spring.jackson.time-zone=UTC` pins the Jackson 3 normalization target.** Spring Boot 4 uses Jackson 3, whose default timezone is UTC and which normalizes JSR-310 values (`OffsetDateTime`, `ZonedDateTime`) to that timezone on serialization — an `OffsetDateTime` carrying `+03:00` is emitted as the equivalent UTC instant with `Z`. The property keeps that target explicit and deterministic instead of relying on the framework default.
- **`spring.jpa.properties.hibernate.jdbc.time_zone=UTC` is a critical piece.** It makes Hibernate interpret JDBC timestamp values as UTC, which matters for `TIMESTAMP` semantics and prevents host-dependent drift. Combined with `TIMESTAMPTZ` columns (which PostgreSQL stores internally in UTC regardless of session), instants become unambiguous.
- **ISO-8601 date strings are the Jackson 3 default.** In Spring Boot 4, Jackson 3 is the default JSON library and `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` is disabled out of the box, so dates serialize as ISO-8601 strings with no property required. The obsolete Spring Boot 2.x property `spring.jackson.serialization.write-dates-as-timestamps` no longer exists (`SerializationFeature` no longer carries it) and must not be used.
- **Testcontainers `TZ=UTC` / `PGTZ=UTC` is defensive.** `TIMESTAMPTZ` storage is unaffected by session timezone; the session zone only matters for `now()`, `CURRENT_DATE`, or raw SQL that depends on the session. Setting it makes `now()` deterministic and covers future raw SQL. The ADR labels it defensive.
- **CI declares `TZ=UTC`.** GitHub `ubuntu-latest` defaults to UTC, but declaring it makes the contract explicit and future-proof.
- **Banned types and `Clock`.** New code must not use `java.util.Date`/`Calendar`/`LocalDateTime` to represent instants. Financial/domain code should depend on an injectable `Clock` (system UTC in production, fixed in tests) rather than calling `Instant.now()` directly, aligning with the existing financial testing guidance.
- **Scheduling rule for DST.** Future local-time scheduling stores region `ZoneId` + local time and resolves the instant at execution time. Rationale over alternatives: (a) storing only `Instant` loses the user's local intent; (b) storing a fixed `OffsetDateTime` is wrong because the offset at execution may differ from the offset at scheduling due to DST; (c) storing `ZoneId` + local time and resolving at execution uses the rules in effect on the target date, which is what users actually mean by "9am local".

## Risks / Trade-offs

- `TimeZone.setDefault` in `main()` can be bypassed by anything that reads the default before `main` runs → Mitigation: production relies on the container environment; the runtime call is a second layer of defense.
- `spring.jackson.time-zone` normalization means a value's original offset is not preserved in API output → Mitigation: this is intentional and aligns with the UTC contract; every serialized date-time value ends with `Z`. Constructing instants with UTC offset remains the rule for clarity.
- The scheduling rule is specified before any scheduling feature exists → Mitigation: the ADR and the spec record it so the future feature implements it without re-deriving the decision under time pressure.
