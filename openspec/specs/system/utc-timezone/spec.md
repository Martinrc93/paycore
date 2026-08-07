# system/utc-timezone Specification

## Purpose

Defines the system-wide requirement that PayCore uses UTC as its canonical timezone for processing, storing, and serializing date-time values, with an explicit rule for scheduling future events in a user's local time across Daylight Saving Time changes.

## Requirements

### Requirement: UTC is the canonical timezone

The system SHALL use UTC as the canonical timezone for all date-time processing. The JVM default, Hibernate/JDBC, and the API serialization layer SHALL all be configured to UTC so that behavior does not depend on the deployment host's local timezone.

#### Scenario: JVM default is UTC

- **WHEN** the application starts
- **THEN** the JVM default timezone is UTC regardless of the host environment

#### Scenario: Database session timezone

- **WHEN** the application opens a JDBC connection to PostgreSQL
- **THEN** date-time values are interpreted and stored as UTC

### Requirement: Instants are stored in UTC

The system SHALL store every point-in-time value (an instant) in UTC. Java code SHALL represent instants with `Instant` (or `OffsetDateTime` with an explicit UTC offset) and persistence SHALL use `TIMESTAMPTZ` columns. Business calendar dates SHALL be represented with `LocalDate` and stored in `DATE` columns.

#### Scenario: Transaction timestamp is an unambiguous instant

- **WHEN** a financial transaction is recorded
- **THEN** its timestamp is stored as a UTC instant in a `TIMESTAMPTZ` column and retains the same instant regardless of the database session timezone

#### Scenario: Business date is a calendar date

- **WHEN** the system stores a business date such as a value date
- **THEN** it is stored as a `LocalDate` calendar date in a `DATE` column, independent of any timezone offset

### Requirement: API serializes date-time values with explicit UTC

The system SHALL serialize date-time values in ISO-8601 with an explicit UTC designator (`Z`). Consumers SHALL be able to interpret the serialized value without knowing the server's timezone.

#### Scenario: Instant is serialized with UTC designator

- **WHEN** the API serializes an instant
- **THEN** the output is an ISO-8601 string ending with `Z` (for example `2026-08-07T18:00:00Z`)

#### Scenario: OffsetDateTime with UTC offset is serialized as Z

- **WHEN** the API serializes an `OffsetDateTime` whose offset is UTC
- **THEN** the output is an ISO-8601 string ending with `Z`

#### Scenario: Non-UTC offset is normalized to UTC

- **WHEN** the API serializes an `OffsetDateTime` carrying a non-UTC offset
- **THEN** the output is normalized to the corresponding UTC instant ending with `Z`, so that every serialized date-time value carries the UTC designator

### Requirement: Future local-time scheduling resolves instants at execution

The system SHALL schedule future events expressed in a user's local time by storing the region `ZoneId` together with the local time, and SHALL resolve the actual instant at execution time using the timezone rules in effect on that date. The system SHALL NOT rely on a fixed offset captured at scheduling time, because Daylight Saving Time changes can shift the effective offset between scheduling and execution.

#### Scenario: Scheduled event across a DST transition

- **WHEN** a payment is scheduled in the user's local time for a future date that falls across a Daylight Saving Time transition
- **THEN** the system resolves the instant at execution time from the stored local time and region `ZoneId`, producing the instant correct for the rules in effect on that date

#### Scenario: Fixed offset is never the scheduling input

- **WHEN** a future event is scheduled in a user's local time
- **THEN** the scheduling input is the region timezone and local time, never a fixed UTC offset captured at scheduling time
