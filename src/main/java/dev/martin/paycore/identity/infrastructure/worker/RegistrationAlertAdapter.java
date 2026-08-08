package dev.martin.paycore.identity.infrastructure.worker;

import dev.martin.paycore.identity.application.port.out.RegistrationAlertPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RegistrationAlertAdapter implements RegistrationAlertPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationAlertAdapter.class);
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z0-9_]{1,64}");
    private final MeterRegistry meterRegistry;

    public RegistrationAlertAdapter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void retryThresholdExceeded(UUID operationId, int attemptCount, String failureCode) {
        String safeCode = SAFE_CODE.matcher(failureCode).matches() ? failureCode : "UNCLASSIFIED";
        LOGGER.warn("Registration retry threshold exceeded operationId={} attemptCount={} failureCode={}",
                operationId, attemptCount, safeCode);
        Counter.builder("paycore.registration.retry.threshold")
                .tag("failure_code", safeCode)
                .register(meterRegistry)
                .increment();
    }
}
