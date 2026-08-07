package dev.martin.paycore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class PaycoreApplicationTests {

  	@Container
		@ServiceConnection
		static PostgreSQLContainer postgres =
				new PostgreSQLContainer("postgres:17")
						.withEnv("TZ", "UTC")
						.withEnv("PGTZ", "UTC");
					

	@Test
	void contextLoads() {
	}

}
