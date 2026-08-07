# ADR-0004: Use UTC as the System Time Zone

## Status

Accepted

## Date

2026-08-07

## Context

PayCore is a financial system that must record unambiguous instants for transactions, payments, and audit trails.

Without an explicit timezone convention, the JVM default, Jackson, Hibernate, and the database can each interpret the same timestamp in a different zone depending on the deployment host. A transaction recorded at the same moment can be serialized as `18:00:00Z` in one environment and `19:00:00+01:00` in another, making the API contract and the audit trail host-dependent.

PayCore may also need to schedule future events (for example, scheduled or recurring payments) expressed in a user's local time. A fixed UTC offset captured at scheduling time is not a safe representation for such events: between today and the future execution date, Daylight Saving Time rules can change the effective offset, so "09:00 America/Argentina/Buenos_Aires" is not the same instant on every date.

## Decision

PayCore uses **UTC as its canonical timezone** for processing, storing, and serializing all date-time values.

- **Instants** (points in time) are represented with `Instant` (or `OffsetDateTime` carrying an explicit UTC offset) and stored in `TIMESTAMPTZ` columns. Stored and exchanged instants are always UTC.
- **Business calendar dates** (for example, value dates) are represented with `LocalDate` and stored in `DATE` columns.
- **API serialization** emits ISO-8601 with an explicit UTC designator (`Z`). Consumers never need to know the server timezone to interpret a value.
- **Presentation** converts to the user's local timezone only at the presentation boundary. The domain, persistence, and API contract always use UTC.
- **JVM default timezone is UTC.** The deployment environment sets `TZ=UTC` (plus `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` as a belt), and `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))` runs at application startup as a defensive measure. Tests run with `user.timezone=UTC`.
- **Hibernate/JDBC timezone is UTC** via `hibernate.jdbc.time_zone`, so timestamp values are interpreted as UTC regardless of host.
- **Future local-time scheduling** stores the region `ZoneId` (for example, `America/Argentina/Buenos_Aires`) together with the local time, and resolves the actual instant at execution time using the timezone rules in effect on the target date. A fixed offset captured at scheduling time is never used as the scheduling input, because DST can change the effective offset between scheduling and execution.
- **Banned in new code**: `java.util.Date`, `Calendar`, and `LocalDateTime` as representations of an instant.
- **`Clock` is injectable.** Domain and financial code depend on a `Clock` (system UTC in production, fixed in tests) instead of calling `Instant.now()` directly, supporting deterministic testing.

## DST Scheduling Rule

When a future event is expressed in a user's local time:

1. Store the region `ZoneId` and the local date-time.
2. At execution time, resolve the instant via `localDateTime.atZone(zoneId)`.
3. Never derive the instant from a fixed offset captured at scheduling time.

This ensures the executed instant reflects the offset actually in effect on the execution date, which is what the user means by "09:00 local".

## Configuration Summary

| Layer | Setting | Role |
|---|---|---|
| JVM default | `TZ=UTC`, `-Duser.timezone=UTC`, `TimeZone.setDefault(UTC)` | Critical |
| Hibernate/JDBC | `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` | Critical |
| Jackson | `spring.jackson.time-zone=UTC` | Normalization target (Jackson 3) |
| Jackson | ISO-8601 string dates (`DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` disabled by default in Jackson 3 / Spring Boot 4) | Contract: ISO-8601 strings |
| Testcontainers | `TZ=UTC`, `PGTZ=UTC` | Defensive (see below) |
| CI | `TZ=UTC` | Deterministic builds |

## Alternatives Considered

### Server-Local Time (Do Nothing)

Let each host interpret timestamps in its own local timezone.

This makes the API contract and audit trail host-dependent and was rejected.

### Fixed Offset Only

Store future scheduled events as a fixed `OffsetDateTime` captured at scheduling time.

This silently produces the wrong instant when DST changes the effective offset between scheduling and execution and was rejected.

### Store Only the Instant for Scheduling

Store only the resolved `Instant` for a local-time schedule.

This loses the user's local intent (a schedule of "09:00 local" cannot be reconstructed from an instant alone) and was rejected.

## Consequences

### Positive

- Deterministic, host-independent instants and API contract.
- Unambiguous audit trail for financial events.
- Future DST changes cannot corrupt scheduled local-time events.
- Testability: injectable `Clock` and fixed timezone make time-dependent tests deterministic.
- Jackson 3 (the Spring Boot 4 default) normalizes JSR-310 values to the mapper timezone on serialization, so API output always ends with `Z` even if a value carries a non-UTC offset.

### Negative

- Developers must be disciplined: although Jackson 3 normalizes serialized values to UTC, code must still construct instants with an explicit UTC offset so that in-memory values are unambiguous.
- `PGTZ`/`TZ` in the test container do **not** affect `TIMESTAMPTZ` storage correctness (PostgreSQL stores `TIMESTAMPTZ` internally in UTC). They only make session-dependent functions such as `now()` and `CURRENT_DATE` deterministic and cover future raw SQL; they are defensive.
- `TimeZone.setDefault` in `main()` can be bypassed by anything reading the default before startup; the deployment environment remains the primary mechanism.
- Data migration: **N/A** — PayCore is greenfield with no existing columns, data, or API consumers. This decision applies to all new code.

## Invariants

Instants are stored and exchanged in UTC.

Scheduled local-time events resolve their instant at execution time using the region `ZoneId`.

Date-time serialization always includes an explicit timezone offset.

`java.util.Date`, `Calendar`, and `LocalDateTime` are never used to represent an instant.
