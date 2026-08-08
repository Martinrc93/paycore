package dev.martin.paycore.identity.infrastructure.worker;

import dev.martin.paycore.identity.application.registration.ProcessRegistrationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
@ConditionalOnProperty(name = "paycore.registration.worker-enabled", havingValue = "true")
public class RegistrationWorker {

    private final ProcessRegistrationService service;
    private final int maxBatchSize;

    public RegistrationWorker(ProcessRegistrationService service,
            @Value("${paycore.registration.worker.max-batch-size:20}") int maxBatchSize) {
        this.service = service;
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        this.maxBatchSize = maxBatchSize;
    }

    @Scheduled(
            fixedDelayString = "${paycore.registration.worker.poll-delay:1s}",
            initialDelayString = "${paycore.registration.worker.initial-delay:5s}")
    void processDueRegistrations() {
        for (int processed = 0; processed < maxBatchSize && service.processNext(); processed++) {
            // Bound each poll so one instance cannot monopolize due work.
        }
    }
}
