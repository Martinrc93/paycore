package dev.martin.paycore.identity.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RegistrationConfigurationTest {

    @Test
    void parsesVersionedBase64DigestSecrets() {
        String first = encoded("first-registration-secret-at-least-32-bytes");
        String second = encoded("second-registration-secret-at-least-32-bytes");

        var secrets = RegistrationConfiguration.parseDigestSecrets("1=" + first + ",2=" + second);

        assertThat(new String(secrets.get(1), StandardCharsets.UTF_8))
                .isEqualTo("first-registration-secret-at-least-32-bytes");
        assertThat(new String(secrets.get(2), StandardCharsets.UTF_8))
                .isEqualTo("second-registration-secret-at-least-32-bytes");
    }

    @Test
    void rejectsDuplicateVersionsAndInvalidBase64() {
        String secret = encoded("registration-secret-at-least-32-bytes");

        assertThatThrownBy(() -> RegistrationConfiguration.parseDigestSecrets(
                "1=" + secret + ",1=" + secret))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegistrationConfiguration.parseDigestSecrets("1=not-base64"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
