package dev.martin.paycore.identity.application.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.martin.paycore.identity.application.port.out.ExternalIdentityProvisioner;
import dev.martin.paycore.identity.application.port.out.RegistrationAlertPort;
import dev.martin.paycore.identity.application.port.out.RegistrationWorkPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.Email;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcessRegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
    private final FakeWorkPort workPort = new FakeWorkPort();
    private final FakeProvisioner provisioner = new FakeProvisioner();
    private final FakeAlertPort alertPort = new FakeAlertPort();
    private final ProcessRegistrationService service = new ProcessRegistrationService(
            workPort, provisioner,
            new RegistrationBackoff(Duration.ofSeconds(5), Duration.ofHours(1), () -> 0.5),
            alertPort, 3, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(2));

    @Test
    void createsIdentityForPendingOperation() {
        workPort.claims.add(claim(RegistrationOperationState.PENDING_IDENTITY, 1));

        assertThat(service.processNext()).isTrue();

        assertThat(provisioner.provisionCalls).isEqualTo(1);
        assertThat(workPort.linkedSubject).isEqualTo("keycloak-subject");
        assertThat(provisioner.emailCalls).isZero();
    }

    @Test
    void sendsActionsAndCompletesLinkedOperation() {
        workPort.claims.add(claim(RegistrationOperationState.IDENTITY_LINKED, 1));

        service.processNext();

        assertThat(provisioner.emailCalls).isEqualTo(1);
        assertThat(workPort.completed).isTrue();
    }

    @Test
    void advancesUsingTimeObservedAfterRemoteCall() {
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(NOW, NOW, NOW.plusSeconds(121));
        ProcessRegistrationService advancingService = new ProcessRegistrationService(
                workPort, provisioner,
                new RegistrationBackoff(Duration.ofSeconds(5), Duration.ofHours(1), () -> 0.5),
                alertPort, 3, advancingClock, Duration.ofMinutes(2));
        workPort.claims.add(claim(RegistrationOperationState.PENDING_IDENTITY, 1));

        advancingService.processNext();

        assertThat(workPort.linkedAt).isEqualTo(NOW.plusSeconds(121));
    }

    @Test
    void schedulesRetryableFailureWithoutTerminalAttemptLimit() {
        workPort.claims.add(claim(RegistrationOperationState.PENDING_IDENTITY, 4));
        provisioner.failure = new ProvisioningException(ProvisioningFailure.RETRYABLE, "temporary");

        service.processNext();

        assertThat(workPort.retryAt).isEqualTo(NOW.plusSeconds(40));
        assertThat(workPort.reconciliationCode).isNull();
        assertThat(alertPort.failureCode).isEqualTo("temporary");
        assertThat(alertPort.attemptCount).isEqualTo(4);
    }

    @Test
    void retryAfterExtendsCalculatedBackoffWithoutMakingOperationTerminal() {
        workPort.claims.add(claim(RegistrationOperationState.PENDING_IDENTITY, 1));
        provisioner.failure = new ProvisioningException(
                ProvisioningFailure.RETRYABLE, "KEYCLOAK_429", Duration.ofMinutes(2), null);

        service.processNext();

        assertThat(workPort.retryAt).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        assertThat(workPort.reconciliationCode).isNull();
    }

    @Test
    void retryAfterCannotExceedMaximumBackoff() {
        workPort.claims.add(claim(RegistrationOperationState.PENDING_IDENTITY, 1));
        provisioner.failure = new ProvisioningException(
                ProvisioningFailure.RETRYABLE, "KEYCLOAK_429", Duration.ofDays(365), null);

        service.processNext();

        assertThat(workPort.retryAt).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void recordsReconciliationFailure() {
        workPort.claims.add(claim(RegistrationOperationState.PENDING_IDENTITY, 1));
        provisioner.failure = new ProvisioningException(
                ProvisioningFailure.RECONCILIATION_REQUIRED, "KEYCLOAK_FORBIDDEN");

        service.processNext();

        assertThat(workPort.reconciliationCode).isEqualTo("KEYCLOAK_FORBIDDEN");
    }

    @Test
    void identityLinkConflictRequiresReconciliationInsteadOfRetrying() {
        workPort.claims.add(claim(RegistrationOperationState.PENDING_IDENTITY, 1));
        workPort.identityLinkConflict = true;

        service.processNext();

        assertThat(workPort.reconciliationCode).isEqualTo("IDENTITY_LINK_CONFLICT");
        assertThat(workPort.retryAt).isNull();
    }

    @Test
    void reportsNoWorkWhenNothingIsDue() {
        assertThat(service.processNext()).isFalse();
    }

    private static ClaimedRegistration claim(RegistrationOperationState state, int attempts) {
        return new ClaimedRegistration(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                new CustomerId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                Email.of("person@example.com"), state, "subject-1",
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), 3, attempts,
                NOW.plusSeconds(120));
    }

    private static final class FakeWorkPort implements RegistrationWorkPort {
        private final Queue<ClaimedRegistration> claims = new ArrayDeque<>();
        private String linkedSubject;
        private Instant linkedAt;
        private boolean completed;
        private Instant retryAt;
        private String reconciliationCode;
        private boolean identityLinkConflict;

        @Override
        public Optional<ClaimedRegistration> claimNext(Instant now, Duration leaseDuration) {
            return Optional.ofNullable(claims.poll());
        }

        @Override
        public boolean renewLease(ClaimedRegistration claim, Instant now, Duration leaseDuration) {
            return true;
        }

        @Override
        public boolean markIdentityLinked(ClaimedRegistration claim, ProvisionedIdentity identity, Instant now) {
            if (identityLinkConflict) {
                throw new RegistrationIntegrityException("IDENTITY_LINK_CONFLICT");
            }
            linkedSubject = identity.subject();
            linkedAt = now;
            return true;
        }

        @Override
        public boolean complete(ClaimedRegistration claim, Instant now) {
            completed = true;
            return true;
        }

        @Override
        public void scheduleRetry(ClaimedRegistration claim, Instant now,
                Instant nextAttemptAt, String failureCode) {
            retryAt = nextAttemptAt;
        }

        @Override
        public void requireReconciliation(ClaimedRegistration claim, String failureCode, Instant now) {
            reconciliationCode = failureCode;
        }
    }

    private static final class FakeProvisioner implements ExternalIdentityProvisioner {
        private int provisionCalls;
        private int emailCalls;
        private ProvisioningException failure;

        @Override
        public ProvisionedIdentity provision(CustomerId customerId, Email email) {
            provisionCalls++;
            failIfConfigured();
            return new ProvisionedIdentity("https://identity.example/realms/paycore", "keycloak-subject");
        }

        @Override
        public void sendRequiredActions(String subject) {
            emailCalls++;
            failIfConfigured();
        }

        private void failIfConfigured() {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class FakeAlertPort implements RegistrationAlertPort {
        private int attemptCount;
        private String failureCode;

        @Override
        public void retryThresholdExceeded(UUID operationId, int attemptCount, String failureCode) {
            this.attemptCount = attemptCount;
            this.failureCode = failureCode;
        }
    }
}
