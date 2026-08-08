package dev.martin.paycore.identity.infrastructure.worker;

import dev.martin.paycore.identity.infrastructure.persistence.RegistrationCleanupAdapter;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "paycore.registration.enabled", havingValue = "true")
public class RegistrationCleanupWorker {

    private final RegistrationCleanupAdapter cleanupAdapter;
    private final Clock clock;

    public RegistrationCleanupWorker(RegistrationCleanupAdapter cleanupAdapter, Clock clock) {
        this.cleanupAdapter = cleanupAdapter;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${paycore.registration.cleanup.delay:1h}",
            initialDelayString = "${paycore.registration.cleanup.initial-delay:1m}")
    void cleanupExpiredRegistrationData() {
        Instant now = clock.instant();
        cleanupAdapter.deleteExpiredTerminal(now);
        cleanupAdapter.deleteExpiredRateLimits(now);
    }
}
