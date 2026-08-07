## Why

PayCore is a financial system that must record unambiguous instants for transactions and payments. Without an explicit timezone convention, the JVM default, Hibernate, Jackson, and the database can each interpret the same timestamp in different zones depending on the deployment host, producing contract and audit inconsistencies. Establish UTC as the canonical timezone across the stack now, while the system is greenfield, so the observable contract and storage semantics are deterministic from the start.

## What Changes

- Add a system-wide requirement that all date/time handling uses UTC as the canonical timezone.
- Define the storage semantics: instants are stored in UTC (`TIMESTAMPTZ` / `Instant`); business dates use `LocalDate` / `DATE`.
- Define the API contract: serialized date-time values are ISO-8601 with an explicit UTC offset (`Z`).
- Define the rule for scheduling future events in a user's local time: store the region `ZoneId` plus local time and resolve the instant at execution time, so Daylight Saving Time changes do not corrupt the scheduled instant.
- Wire the convention in configuration: JVM default timezone, Jackson, Hibernate/JDBC, the test PostgreSQL container, and CI.
- Prohibit `java.util.Date`/`Calendar`/`LocalDateTime` as representations of instants in new code; require injectable `Clock` for testability.

## Capabilities

### New Capabilities

- `system/utc-timezone`: system-wide requirements for canonical UTC date/time handling, including storage semantics, API serialization contract, and scheduling across DST changes.

### Modified Capabilities

- None. No existing specs are present in `openspec/specs/`.

## Impact

- Configuration: `src/main/resources/application.properties` (Jackson, JPA/Hibernate timezone), `PaycoreApplication` (JVM default), `pom.xml` (Surefire test JVM), `PaycoreApplicationTests` (Testcontainers `TZ`/`PGTZ`), `.github/workflows/ci.yml` (explicit `TZ`).
- New test: `TimeZoneConfigurationTests` verifying the JVM default and the Spring `ObjectMapper` serialization contract.
- Documentation: new ADR-0004 recording the decision and its consequences.
- No existing database columns, data, or API consumers exist; no migration of existing data is required.
