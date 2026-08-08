package dev.martin.paycore.identity.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.martin.paycore.identity.application.registration.ProvisionedIdentity;
import dev.martin.paycore.identity.application.registration.ProvisioningException;
import dev.martin.paycore.identity.application.registration.ProvisioningFailure;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.Email;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class KeycloakProvisioningAdapterTest {

    private static final String BASE_URL = "https://identity.example";
    private static final String CUSTOMER_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void responseLossRecoversOwnedUserBeforeRetryingCreate() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectToken(server);
        server.expect(requestTo(BASE_URL + "/admin/realms/paycore/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new ResourceAccessException("create response lost");
                });
        server.expect(requestTo(BASE_URL
                        + "/admin/realms/paycore/users?username=person%40example.com&exact=true&briefRepresentation=false"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{
                          "id": "subject-1",
                          "username": "person@example.com",
                          "attributes": {"paycore_customer_id": ["11111111-1111-1111-1111-111111111111"]}
                        }]
                        """, MediaType.APPLICATION_JSON));

        ProvisionedIdentity identity = adapter(builder).provision(customerId(), Email.of("person@example.com"));

        assertThat(identity).isEqualTo(new ProvisionedIdentity(
                "https://identity.example/realms/paycore", "subject-1"));
        server.verify();
    }

    @Test
    void ambiguousCandidatesAreNeverLinkedEvenWhenOneAppearsOwned() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectToken(server);
        server.expect(requestTo(BASE_URL + "/admin/realms/paycore/users"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(BASE_URL
                        + "/admin/realms/paycore/users?username=person%40example.com&exact=true&briefRepresentation=false"))
                .andRespond(withSuccess("""
                        [
                          {
                            "id": "owned-subject",
                            "attributes": {"paycore_customer_id": ["11111111-1111-1111-1111-111111111111"]}
                          },
                          {
                            "id": "unrelated-subject",
                            "attributes": {"paycore_customer_id": ["22222222-2222-2222-2222-222222222222"]}
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter(builder).provision(customerId(), Email.of("person@example.com")))
                .isInstanceOfSatisfying(ProvisioningException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(ProvisioningFailure.RECONCILIATION_REQUIRED);
                    assertThat(exception.code()).isEqualTo("KEYCLOAK_OWNERSHIP_CONFLICT");
                });
        server.verify();
    }

    @Test
    void sendsTimeLimitedRequiredActionsAgainAfterAmbiguousOutcome() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (int attempt = 0; attempt < 2; attempt++) {
            expectToken(server);
            server.expect(method(HttpMethod.PUT))
                    .andExpect(request -> assertThat(request.getURI().toString())
                            .contains("/admin/realms/paycore/users/subject-1/execute-actions-email")
                            .contains("client_id=paycore-provisioner")
                            .contains("redirect_uri=https://paycore.example/registration-complete")
                            .contains("lifespan=3600"))
                    .andExpect(content().json("[\"VERIFY_EMAIL\",\"UPDATE_PASSWORD\"]"))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));
        }
        KeycloakProvisioningAdapter adapter = adapter(builder);

        adapter.sendRequiredActions("subject-1");
        adapter.sendRequiredActions("subject-1");

        server.verify();
    }

    @Test
    void honorsRetryAfterFromKeycloakRateLimit() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/realms/paycore/protocol/openid-connect/token"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "120"));

        assertThatThrownBy(() -> adapter(builder).provision(customerId(), Email.of("person@example.com")))
                .isInstanceOfSatisfying(ProvisioningException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(ProvisioningFailure.RETRYABLE);
                    assertThat(exception.code()).isEqualTo("KEYCLOAK_SERVICE_TOKEN_429");
                    assertThat(exception.retryAfter()).contains(Duration.ofMinutes(2));
                });
        server.verify();
    }

    @Test
    void honorsRetryAfterHttpDate() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/realms/paycore/protocol/openid-connect/token"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "Sat, 8 Aug 2026 12:02:00 GMT"));

        assertThatThrownBy(() -> adapter(builder).provision(customerId(), Email.of("person@example.com")))
                .isInstanceOfSatisfying(ProvisioningException.class,
                        exception -> assertThat(exception.retryAfter()).contains(Duration.ofMinutes(2)));
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("statusClassifications")
    void classifiesKeycloakStatusCodes(HttpStatus status, ProvisioningFailure expectedFailure) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/realms/paycore/protocol/openid-connect/token"))
                .andRespond(withStatus(status));

        assertThatThrownBy(() -> adapter(builder).provision(customerId(), Email.of("person@example.com")))
                .isInstanceOfSatisfying(ProvisioningException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(expectedFailure));
        server.verify();
    }

    @Test
    void classifiesConnectionFailureAsRetryable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/realms/paycore/protocol/openid-connect/token"))
                .andRespond(request -> {
                    throw new ResourceAccessException("connection refused");
                });

        assertThatThrownBy(() -> adapter(builder).provision(customerId(), Email.of("person@example.com")))
                .isInstanceOfSatisfying(ProvisioningException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(ProvisioningFailure.RETRYABLE));
        server.verify();
    }

    @Test
    void missingOwnedUserAfterConflictRequiresReconciliation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectToken(server);
        server.expect(requestTo(BASE_URL + "/admin/realms/paycore/users"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(BASE_URL
                        + "/admin/realms/paycore/users?username=person%40example.com&exact=true&briefRepresentation=false"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter(builder).provision(customerId(), Email.of("person@example.com")))
                .isInstanceOfSatisfying(ProvisioningException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(ProvisioningFailure.RECONCILIATION_REQUIRED);
                    assertThat(exception.code()).isEqualTo("KEYCLOAK_OWNED_USER_NOT_FOUND");
                });
        server.verify();
    }

    private static Stream<Arguments> statusClassifications() {
        return Stream.of(
                Arguments.of(HttpStatus.BAD_REQUEST, ProvisioningFailure.RECONCILIATION_REQUIRED),
                Arguments.of(HttpStatus.UNAUTHORIZED, ProvisioningFailure.RECONCILIATION_REQUIRED),
                Arguments.of(HttpStatus.FORBIDDEN, ProvisioningFailure.RECONCILIATION_REQUIRED),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, ProvisioningFailure.RETRYABLE),
                Arguments.of(HttpStatus.SERVICE_UNAVAILABLE, ProvisioningFailure.RETRYABLE));
    }

    private static void expectToken(MockRestServiceServer server) {
        server.expect(requestTo(BASE_URL + "/realms/paycore/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"service-token\"}", MediaType.APPLICATION_JSON));
    }

    private static CustomerId customerId() {
        return new CustomerId(UUID.fromString(CUSTOMER_ID));
    }

    private static KeycloakProvisioningAdapter adapter(RestClient.Builder builder) {
        return new KeycloakProvisioningAdapter(builder, new KeycloakProvisioningProperties(
                BASE_URL, "paycore", BASE_URL + "/realms/paycore",
                "paycore-provisioner", "contract-test-secret",
                "https://paycore.example/registration-complete", Duration.ofHours(1)),
                Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC));
    }
}
