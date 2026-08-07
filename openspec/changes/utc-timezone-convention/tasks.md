## 1. Documentation

- [x] 1.1 Write ADR-0004 "Use UTC as the System Time Zone" in docs/adr/ recording the decision, the DST scheduling rule, the defensive nature of `spring.jackson.time-zone`, and the "N/A greenfield migration" note
- [x] 1.2 Add the UTC timezone convention to AGENTS.md conventions for future code

## 2. Configuration

- [x] 2.1 Add `spring.jackson.time-zone=UTC` and `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` to application.properties (note: `write-dates-as-timestamps` was removed — it does not exist in Jackson 3 / Spring Boot 4; ISO-8601 strings are the default)
- [x] 2.2 Set `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))` as the first statement of `PaycoreApplication.main()`
- [x] 2.3 Configure Maven Surefire in pom.xml to run the test JVM with `user.timezone=UTC`
- [x] 2.4 Set `TZ=UTC` and `PGTZ=UTC` environment variables on the Testcontainers PostgreSQL container in PaycoreApplicationTests
- [x] 2.5 Declare `TZ=UTC` in the CI workflow environment

## 3. Tests

- [x] 3.1 Create TimeZoneConfigurationTests asserting the JVM default is UTC, the Spring ObjectMapper serializes `Instant` as `Z`, serializes UTC `OffsetDateTime` as `Z`, normalizes a non-UTC offset to `Z`, and that the `hibernate.jdbc.time_zone` property is `UTC`

## 4. Verification

- [x] 4.1 Run `.\mvnw.cmd -Dtest=TimeZoneConfigurationTests,PaycoreApplicationTests test` — 6 tests, 0 failures
- [x] 4.2 Run the full `.\mvnw.cmd test` suite — 6 tests, 0 failures, BUILD SUCCESS (supersedes the stale AGENTS.md baseline)
- [x] 4.3 Run `openspec validate --specs` and sync the delta spec to the main spec — validated, synced to openspec/specs/system/utc-timezone/spec.md
