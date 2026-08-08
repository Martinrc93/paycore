package dev.martin.paycore.identity.application.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.identity.application.port.out.RegistrationAcceptancePort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterCustomerServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
    private final RecordingAcceptancePort acceptancePort = new RecordingAcceptancePort();
    private final RegisterCustomerService service = new RegisterCustomerService(
            acceptancePort,
            new IdempotencyDigester(1, Map.of(
                    1, "registration-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8))),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> new CustomerId(UUID.fromString("11111111-1111-1111-1111-111111111111")));

    @Test
    void acceptsNewRegistrationForAsynchronousProcessing() {
        RegistrationResponse response = service.register(
                new RegisterCustomerCommand("key-1", " Person@Example.com ", CustomerType.INDIVIDUAL));

        assertThat(response).isEqualTo(RegistrationResponse.accepted());
        assertThat(acceptancePort.intent.customer().email().value()).isEqualTo("person@example.com");
        assertThat(acceptancePort.intent.customer().status().name()).isEqualTo("PROVISIONING");
        assertThat(acceptancePort.intent.expiresAt()).isEqualTo(NOW.plusSeconds(24 * 60 * 60));
    }

    @Test
    void returnsSameAcceptedResponseForDuplicateSuppressionOrReplay() {
        acceptancePort.result = RegistrationAcceptanceResult.ACCEPTED;

        RegistrationResponse response = service.register(
                new RegisterCustomerCommand("same-key", "person@example.com", CustomerType.BUSINESS));

        assertThat(response).isEqualTo(RegistrationResponse.accepted());
    }

    @Test
    void rejectsIdempotencyKeyUsedForAnotherPayload() {
        acceptancePort.result = RegistrationAcceptanceResult.CONFLICT;

        assertThatThrownBy(() -> service.register(
                new RegisterCustomerCommand("reused-key", "other@example.com", CustomerType.INDIVIDUAL)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rejectsInvalidIdempotencyKeyBeforeAcceptance() {
        assertThatThrownBy(() -> service.register(
                new RegisterCustomerCommand("", "person@example.com", CustomerType.INDIVIDUAL)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(acceptancePort.calls).isZero();
    }

    @Test
    void neverCallsExternalIdentityProviderOnAcceptancePath() {
        service.register(new RegisterCustomerCommand("key-1", "person@example.com", CustomerType.INDIVIDUAL));

        assertThat(acceptancePort.calls).isEqualTo(1);
    }

    private static final class RecordingAcceptancePort implements RegistrationAcceptancePort {
        private RegistrationIntent intent;
        private RegistrationAcceptanceResult result = RegistrationAcceptanceResult.ACCEPTED;
        private int calls;

        @Override
        public RegistrationAcceptanceResult accept(RegistrationIntent intent) {
            this.intent = intent;
            calls++;
            return result;
        }
    }
}
