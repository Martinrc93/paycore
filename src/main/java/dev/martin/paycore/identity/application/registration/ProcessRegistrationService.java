package dev.martin.paycore.identity.application.registration;

import dev.martin.paycore.identity.application.port.out.ExternalIdentityProvisioner;
import dev.martin.paycore.identity.application.port.out.RegistrationWorkPort;
import dev.martin.paycore.identity.application.port.out.RegistrationAlertPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class ProcessRegistrationService {

    private final RegistrationWorkPort workPort;
    private final ExternalIdentityProvisioner provisioner;
    private final RegistrationBackoff backoff;
    private final RegistrationAlertPort alertPort;
    private final int alertThreshold;
    private final Clock clock;
    private final Duration leaseDuration;

    public ProcessRegistrationService(RegistrationWorkPort workPort, ExternalIdentityProvisioner provisioner,
            RegistrationBackoff backoff, RegistrationAlertPort alertPort, int alertThreshold,
            Clock clock, Duration leaseDuration) {
        this.workPort = Objects.requireNonNull(workPort, "workPort");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.alertPort = Objects.requireNonNull(alertPort, "alertPort");
        if (alertThreshold < 1) {
            throw new IllegalArgumentException("alertThreshold must be positive");
        }
        this.alertThreshold = alertThreshold;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
    }

    public boolean processNext() {
        Optional<ClaimedRegistration> next = workPort.claimNext(clock.instant(), leaseDuration);
        if (next.isEmpty()) {
            return false;
        }

        ClaimedRegistration claim = next.get();
        if (!workPort.renewLease(claim, clock.instant(), leaseDuration)) {
            return true;
        }
        try {
            if (claim.state() == RegistrationOperationState.PENDING_IDENTITY) {
                ProvisionedIdentity identity = provisioner.provision(claim.customerId(), claim.email());
                workPort.markIdentityLinked(claim, identity, clock.instant());
            } else if (claim.state() == RegistrationOperationState.IDENTITY_LINKED) {
                provisioner.sendRequiredActions(claim.externalSubject());
                workPort.complete(claim, clock.instant());
            }
        } catch (ProvisioningException exception) {
            Instant failedAt = clock.instant();
            if (exception.failure() == ProvisioningFailure.RETRYABLE) {
                Duration delay = backoff.delayForAttempt(claim.attemptCount());
                if (exception.retryAfter().isPresent() && exception.retryAfter().get().compareTo(delay) > 0) {
                    delay = exception.retryAfter().get();
                }
                delay = backoff.cap(delay);
                workPort.scheduleRetry(claim,
                        failedAt, failedAt.plus(delay), exception.code());
                if (claim.attemptCount() >= alertThreshold) {
                    alertPort.retryThresholdExceeded(
                            claim.operationId(), claim.attemptCount(), exception.code());
                }
            } else {
                workPort.requireReconciliation(claim, exception.code(), failedAt);
            }
        } catch (RegistrationIntegrityException exception) {
            workPort.requireReconciliation(claim, exception.code(), clock.instant());
        }
        return true;
    }
}
