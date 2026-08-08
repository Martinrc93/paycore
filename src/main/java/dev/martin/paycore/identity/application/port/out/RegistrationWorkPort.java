package dev.martin.paycore.identity.application.port.out;

import dev.martin.paycore.identity.application.registration.ClaimedRegistration;
import dev.martin.paycore.identity.application.registration.ProvisionedIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface RegistrationWorkPort {

    Optional<ClaimedRegistration> claimNext(Instant now, Duration leaseDuration);

    boolean renewLease(ClaimedRegistration claim, Instant now, Duration leaseDuration);

    boolean markIdentityLinked(ClaimedRegistration claim, ProvisionedIdentity identity, Instant now);

    boolean complete(ClaimedRegistration claim, Instant now);

    void scheduleRetry(ClaimedRegistration claim, Instant now, Instant nextAttemptAt, String failureCode);

    void requireReconciliation(ClaimedRegistration claim, String failureCode, Instant now);
}
