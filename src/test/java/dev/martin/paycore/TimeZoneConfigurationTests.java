package dev.martin.paycore;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class TimeZoneConfigurationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres =
			new PostgreSQLContainer("postgres:17")
					.withEnv("TZ", "UTC")
					.withEnv("PGTZ", "UTC");

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	Environment environment;

	@Test
	void jvmDefaultTimezoneIsUtc() {
		assertThat(TimeZone.getDefault().toZoneId().getRules().getOffset(Instant.now()))
				.isEqualTo(ZoneOffset.UTC);
	}

	@Test
	void instantSerializesWithUtcDesignator() throws Exception {
		Instant instant = Instant.parse("2026-08-07T18:00:00Z");
		assertThat(objectMapper.writeValueAsString(instant)).isEqualTo("\"2026-08-07T18:00:00Z\"");
	}

	@Test
	void utcOffsetDateTimeSerializesAsZ() throws Exception {
		OffsetDateTime offsetDateTime = OffsetDateTime.of(2026, 8, 7, 18, 0, 0, 0, ZoneOffset.UTC);
		assertThat(objectMapper.writeValueAsString(offsetDateTime)).isEqualTo("\"2026-08-07T18:00:00Z\"");
	}

	@Test
	void nonUtcOffsetDateTimeIsNormalizedToUtc() throws Exception {
		OffsetDateTime offsetDateTime = OffsetDateTime.of(2026, 8, 7, 18, 0, 0, 0, ZoneOffset.ofHours(3));
		assertThat(objectMapper.writeValueAsString(offsetDateTime)).isEqualTo("\"2026-08-07T15:00:00Z\"");
	}

	@Test
	void hibernateJdbcTimeZoneIsUtc() {
		assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
	}

}
