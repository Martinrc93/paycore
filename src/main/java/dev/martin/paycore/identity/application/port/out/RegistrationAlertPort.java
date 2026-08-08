package dev.martin.paycore.identity.application.port.out;

import java.util.UUID;

public interface RegistrationAlertPort {

    void retryThresholdExceeded(UUID operationId, int attemptCount, String failureCode);
}
