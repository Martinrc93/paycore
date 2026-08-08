package dev.martin.paycore.identity.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.identity.application.registration.ProvisionedIdentity;
import dev.martin.paycore.identity.application.registration.ProvisioningException;
import dev.martin.paycore.identity.application.registration.ProvisioningFailure;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.Email;
import java.time.Duration;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class KeycloakProvisioningAdapterContractTest {

    @Container
    static GenericContainer<?> keycloak = new GenericContainer<>(
            DockerImageName.parse("quay.io/keycloak/keycloak:26.5.2"))
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("PAYCORE_KEYCLOAK_PROVISIONER_CLIENT_SECRET", "contract-test-secret")
            .withEnv("PAYCORE_REGISTRATION_REDIRECT_URI", "https://paycore.example/registration-complete")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("deploy/keycloak/paycore-realm.json").toAbsolutePath()),
                    "/opt/keycloak/data/import/paycore-realm.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/paycore").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @Test
    void createsOwnedPasswordlessUserAndRecoversItIdempotently() throws Exception {
        String baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
        KeycloakProvisioningProperties properties = new KeycloakProvisioningProperties(
                baseUrl, "paycore", baseUrl + "/realms/paycore",
                "paycore-provisioner", "contract-test-secret",
                "https://paycore.example/registration-complete", Duration.ofHours(1));
        KeycloakProvisioningAdapter adapter = new KeycloakProvisioningAdapter(
                RestClient.builder().baseUrl(baseUrl), properties, Clock.systemUTC());
        CustomerId customerId = new CustomerId(
                UUID.fromString("11111111-1111-1111-1111-111111111111"));

        ProvisionedIdentity created = adapter.provision(customerId, Email.of("person@example.com"));
        assertPasswordlessOwnedUser(baseUrl, customerId, created.subject());
        assertServiceAccountHasOnlyUserManagementRoles(baseUrl);
        ProvisionedIdentity recovered = adapter.provision(customerId, Email.of("person@example.com"));

        assertThat(created.issuer()).isEqualTo(baseUrl + "/realms/paycore");
        assertThat(created.subject()).isNotBlank();
        assertThat(recovered).isEqualTo(created);
    }

    @Test
    void refusesUnrelatedUserWithSameCanonicalUsernameWithoutModifyingIt() {
        String baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        String token = serviceToken(client);
        String unrelatedCustomerId = "22222222-2222-2222-2222-222222222222";
        client.post().uri("/admin/realms/paycore/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username", "unrelated@example.com",
                        "email", "unrelated@example.com",
                        "enabled", true,
                        "attributes", Map.of("paycore_customer_id", List.of(unrelatedCustomerId))))
                .retrieve().toBodilessEntity();
        KeycloakProvisioningAdapter adapter = adapter(baseUrl);

        assertThatThrownBy(() -> adapter.provision(
                new CustomerId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                Email.of("unrelated@example.com")))
                .isInstanceOfSatisfying(ProvisioningException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(ProvisioningFailure.RECONCILIATION_REQUIRED);
                    assertThat(exception.code()).isEqualTo("KEYCLOAK_OWNERSHIP_CONFLICT");
                });

        assertThat(loadUsers(client, token, "unrelated@example.com"))
                .singleElement()
                .satisfies(user -> assertThat(user.get("attributes"))
                        .isEqualTo(Map.of("paycore_customer_id", List.of(unrelatedCustomerId))));
    }

    @SuppressWarnings("unchecked")
    private static void assertPasswordlessOwnedUser(String baseUrl, CustomerId customerId, String subject) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        String token = serviceToken(client);
        List<Map<String, Object>> users = loadUsers(client, token, "person@example.com");

        assertThat(users).singleElement().satisfies(user -> {
            assertThat(user.get("id")).isEqualTo(subject);
            assertThat(user.get("username")).isEqualTo("person@example.com");
            assertThat(user.get("email")).isEqualTo("person@example.com");
            assertThat(user.get("enabled")).isEqualTo(true);
            assertThat(user.get("emailVerified")).isEqualTo(false);
            assertThat((List<String>) user.get("requiredActions"))
                    .containsExactlyInAnyOrder("VERIFY_EMAIL", "UPDATE_PASSWORD");
            assertThat(user.get("attributes"))
                    .as("full Keycloak user representation must retain ownership attribute: %s", user)
                    .isEqualTo(Map.of("paycore_customer_id", List.of(customerId.value().toString())));
        });
        List<Map<String, Object>> credentials = client.get()
                .uri("/admin/realms/paycore/users/{subject}/credentials", subject)
                .header("Authorization", "Bearer " + token)
                .retrieve().body(List.class);
        assertThat(credentials).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static void assertServiceAccountHasOnlyUserManagementRoles(String baseUrl) throws Exception {
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        String token = serviceToken(client);
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        Map<String, Object> claims = new ObjectMapper().readValue(payload, Map.class);
        Map<String, Object> resourceAccess = (Map<String, Object>) claims.get("resource_access");
        Map<String, Object> realmManagement = (Map<String, Object>) resourceAccess.get("realm-management");

        assertThat((List<String>) realmManagement.get("roles")).containsExactly("manage-users");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> loadUsers(RestClient client, String token, String username) {
        return client.get()
                .uri("/admin/realms/paycore/users?username={username}&exact=true&briefRepresentation=false", username)
                .header("Authorization", "Bearer " + token)
                .retrieve().body(List.class);
    }

    @SuppressWarnings("unchecked")
    private static String serviceToken(RestClient client) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "paycore-provisioner");
        form.add("client_secret", "contract-test-secret");
        Map<String, Object> response = client.post()
                .uri("/realms/paycore/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
        return response.get("access_token").toString();
    }

    private static KeycloakProvisioningAdapter adapter(String baseUrl) {
        return new KeycloakProvisioningAdapter(RestClient.builder().baseUrl(baseUrl),
                new KeycloakProvisioningProperties(
                        baseUrl, "paycore", baseUrl + "/realms/paycore",
                        "paycore-provisioner", "contract-test-secret",
                        "https://paycore.example/registration-complete", Duration.ofHours(1)),
                Clock.systemUTC());
    }
}
