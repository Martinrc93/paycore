package dev.martin.paycore.identity.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class KeycloakProvisioningPropertiesTest {

    @Test
    void requiresTlsForNonLoopbackKeycloakEndpoints() {
        assertThatThrownBy(() -> properties("http://identity.internal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> properties("https://identity.example")).doesNotThrowAnyException();
        assertThatCode(() -> properties("http://127.0.0.1:8080")).doesNotThrowAnyException();
    }

    private static KeycloakProvisioningProperties properties(String baseUrl) {
        return new KeycloakProvisioningProperties(
                baseUrl, "paycore", "https://identity.example/realms/paycore",
                "paycore-provisioner", "secret", "https://paycore.example/complete",
                Duration.ofHours(1));
    }
}
