package dev.martin.paycore.identity.application.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.identity.domain.model.CustomerType;
import dev.martin.paycore.identity.domain.model.Email;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdempotencyDigesterTest {

    @Test
    void createsVersionedCandidatesForKeyRotation() {
        IdempotencyDigester digester = new IdempotencyDigester(2, secrets());

        IdempotencyDigests result = digester.digest("request-key");

        assertThat(result.primary().version()).isEqualTo(2);
        assertThat(result.candidates()).extracting(VersionedDigest::version).containsExactly(2, 1);
        assertThat(result.candidates()).noneMatch(digest -> digest.digest().contains("request-key"));
    }

    @Test
    void fingerprintsCanonicalRequestDeterministically() {
        IdempotencyDigester digester = new IdempotencyDigester(2, secrets());

        String first = digester.fingerprint(Email.of("Person@Example.com"), CustomerType.INDIVIDUAL);
        String second = digester.fingerprint(Email.of(" person@example.COM "), CustomerType.INDIVIDUAL);

        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void rejectsKeysOutsideUtf8ByteLimit() {
        IdempotencyDigester digester = new IdempotencyDigester(2, secrets());

        assertThatThrownBy(() -> digester.digest(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> digester.digest("é".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<Integer, byte[]> secrets() {
        Map<Integer, byte[]> secrets = new LinkedHashMap<>();
        secrets.put(2, "current-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));
        secrets.put(1, "previous-secret-at-least-32-byte".getBytes(StandardCharsets.UTF_8));
        return secrets;
    }
}
