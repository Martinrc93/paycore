package dev.martin.paycore.identity.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.martin.paycore.identity.application.port.out.ExternalIdentityProvisioner;
import dev.martin.paycore.identity.application.registration.ProvisionedIdentity;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.Email;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@AutoConfigureMockMvc
@Import(RegistrationControllerTest.SlowKeycloakConfiguration.class)
@SpringBootTest(properties = {
        "paycore.registration.enabled=true",
        "paycore.registration.worker-enabled=false",
        "paycore.registration.idempotency-current-version=1",
        "paycore.registration.idempotency-secrets=1=cmVnaXN0cmF0aW9uLXNlY3JldC1hdC1sZWFzdC0zMi1ieXRlcw==",
        "paycore.registration.rate-limit-secret=rate-limit-secret-at-least-32-bytes",
        "paycore.registration.keycloak.base-url=http://127.0.0.1:1",
        "paycore.registration.keycloak.realm=paycore",
        "paycore.registration.keycloak.issuer=http://127.0.0.1:1/realms/paycore",
        "paycore.registration.keycloak.client-id=paycore-provisioner",
        "paycore.registration.keycloak.client-secret=unused-test-secret",
        "paycore.registration.keycloak.redirect-uri=https://paycore.example/registration-complete"
})
class RegistrationControllerTest {

    private static final String ACCEPTED = "{\"message\":\"If registration can proceed, check your email.\"}";
    private static final AtomicInteger KEYCLOAK_CALLS = new AtomicInteger();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC").withEnv("PGTZ", "UTC");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        KEYCLOAK_CALLS.set(0);
        jdbcClient.sql("TRUNCATE TABLE registration_operations, external_identities, customers, registration_rate_limits")
                .update();
    }

    @Test
    void acceptsIndividualAndBusinessWithGenericResponse() throws Exception {
        register("individual-key", "person@example.com", "INDIVIDUAL")
                .andExpect(status().isAccepted())
                .andExpect(content().json(ACCEPTED));
        register("business-key", "business@example.com", "BUSINESS")
                .andExpect(status().isAccepted())
                .andExpect(content().json(ACCEPTED));
    }

    @Test
    void duplicateEmailHasSameStatusAndBody() throws Exception {
        register("first-key", "person@example.com", "INDIVIDUAL")
                .andExpect(status().isAccepted()).andExpect(content().json(ACCEPTED));
        register("second-key", " PERSON@EXAMPLE.COM ", "BUSINESS")
                .andExpect(status().isAccepted()).andExpect(content().json(ACCEPTED));
    }

    @Test
    void rejectsMissingKeyInvalidTypeAndCredentialFields() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"person@example.com\",\"customerType\":\"INDIVIDUAL\"}"))
                .andExpect(status().isBadRequest());
        register("key-1", "person@example.com", "UNKNOWN")
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/customers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"person@example.com","customerType":"INDIVIDUAL","password":"secret"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(jdbcClient.sql("SELECT count(*) FROM customers").query(Long.class).single()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\\\"quoted\\\"@example.com",
            "person name@example.com",
            "person@-example.com",
            "person@example..com",
            "person@localhost",
            "josé@example.com",
            "person@examplé.com"
    })
    void rejectsUnsupportedEmailFormsWithoutCreatingWork(String email) throws Exception {
        register("key-1", email, "INDIVIDUAL").andExpect(status().isBadRequest());

        assertThat(jdbcClient.sql("SELECT count(*) FROM registration_operations").query(Long.class).single())
                .isZero();
    }

    @Test
    void enforcesIdempotencyKeyByteBoundariesAndRejectsCredentialObject() throws Exception {
        register("a".repeat(128), "first@example.com", "INDIVIDUAL")
                .andExpect(status().isAccepted());
        register("a".repeat(129), "second@example.com", "INDIVIDUAL")
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/customers")
                        .header("Idempotency-Key", "key-credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"third@example.com","customerType":"BUSINESS","credentials":{}}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(jdbcClient.sql("SELECT count(*) FROM registration_operations").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void returnsConflictWhenKeyIsReusedForDifferentPayload() throws Exception {
        register("same-key", "person@example.com", "INDIVIDUAL").andExpect(status().isAccepted());

        register("same-key", "other@example.com", "BUSINESS")
                .andExpect(status().isConflict());
    }

    @Test
    void reconciliationStateRemainsTheOriginalGenericAcceptedResult() throws Exception {
        register("reconciliation-key", "person@example.com", "INDIVIDUAL")
                .andExpect(status().isAccepted()).andExpect(content().json(ACCEPTED));
        jdbcClient.sql("""
                        UPDATE registration_operations
                        SET state='RECONCILIATION_REQUIRED', next_attempt_at=NULL,
                            failure_code='KEYCLOAK_CONFIGURATION'
                        """).update();

        register("reconciliation-key", "person@example.com", "INDIVIDUAL")
                .andExpect(status().isAccepted())
                .andExpect(content().json(ACCEPTED));

        assertThat(jdbcClient.sql("SELECT count(*) FROM registration_operations")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void acceptanceLatencyDoesNotDependOnUnavailableKeycloakOrEmailExistence() throws Exception {
        registerFrom("seed-key", "existing@example.com", "INDIVIDUAL", "198.51.100.1")
                .andExpect(status().isAccepted());

        long existingStarted = System.nanoTime();
        var existing = registerFrom("existing-key", "existing@example.com", "BUSINESS", "198.51.100.2")
                .andExpect(status().isAccepted()).andReturn().getResponse();
        Duration existingDuration = Duration.ofNanos(System.nanoTime() - existingStarted);
        long newStarted = System.nanoTime();
        var fresh = registerFrom("new-key", "new@example.com", "INDIVIDUAL", "198.51.100.3")
                .andExpect(status().isAccepted()).andReturn().getResponse();
        Duration newDuration = Duration.ofNanos(System.nanoTime() - newStarted);

        assertThat(existingDuration).isLessThan(Duration.ofSeconds(2));
        assertThat(newDuration).isLessThan(Duration.ofSeconds(2));
        assertThat(existingDuration.minus(newDuration).abs()).isLessThan(Duration.ofMillis(500));
        assertThat(fresh.getContentAsString()).isEqualTo(existing.getContentAsString());
        assertThat(KEYCLOAK_CALLS).hasValue(0);
    }

    @Test
    void rateLimitReturnsGenericRetryGuidanceBeforeLookup() throws Exception {
        for (int request = 0; request < 5; request++) {
            register("key-" + request, "limited@example.com", "INDIVIDUAL")
                    .andExpect(status().isAccepted());
        }

        register("key-over-limit", "limited@example.com", "INDIVIDUAL")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void sourceRateLimitIsIdenticalForExistingAndNewEmails() throws Exception {
        registerFrom("seed-key", "existing@example.com", "INDIVIDUAL", "198.51.100.1")
                .andExpect(status().isAccepted());
        for (int request = 0; request < 20; request++) {
            registerFrom("source-key-" + request, "source-" + request + "@example.com",
                    "INDIVIDUAL", "198.51.100.2")
                    .andExpect(status().isAccepted());
        }

        var existing = registerFrom("limited-existing", "existing@example.com", "INDIVIDUAL", "198.51.100.2")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse();
        var fresh = registerFrom("limited-new", "never-seen@example.com", "INDIVIDUAL", "198.51.100.2")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse();

        assertThat(fresh.getContentAsString()).isEqualTo(existing.getContentAsString());
        assertThat(fresh.getHeader("Retry-After")).isEqualTo(existing.getHeader("Retry-After"));
        assertThat(jdbcClient.sql("SELECT count(*) FROM customers WHERE email='never-seen@example.com'")
                .query(Long.class).single()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions register(
            String key, String email, String customerType) throws Exception {
        return registerFrom(key, email, customerType, "203.0.113.10");
    }

    private org.springframework.test.web.servlet.ResultActions registerFrom(
            String key, String email, String customerType, String source) throws Exception {
        return mockMvc.perform(post("/api/customers")
                .header("Idempotency-Key", key)
                .header("X-Forwarded-For", source)
                .with(request -> {
                    request.setRemoteAddr(source);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","customerType":"%s"}
                        """.formatted(email, customerType)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowKeycloakConfiguration {

        @Bean
        ExternalIdentityProvisioner slowExternalIdentityProvisioner() {
            return new ExternalIdentityProvisioner() {
                @Override
                public ProvisionedIdentity provision(CustomerId customerId, Email email) {
                    delay();
                    return new ProvisionedIdentity("https://identity.example/realms/paycore", "subject-1");
                }

                @Override
                public void sendRequiredActions(String subject) {
                    delay();
                }

                private void delay() {
                    KEYCLOAK_CALLS.incrementAndGet();
                    try {
                        Thread.sleep(Duration.ofSeconds(3));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
            };
        }
    }
}
