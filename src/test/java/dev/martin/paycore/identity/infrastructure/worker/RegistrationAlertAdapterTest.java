package dev.martin.paycore.identity.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RegistrationAlertAdapterTest {

    @Test
    void redactsUntrustedFailureContentFromLogsAndMetricLabels(CapturedOutput output) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RegistrationAlertAdapter adapter = new RegistrationAlertAdapter(registry);
        String sensitive = "person@example.com bearer-secret partial-subject";

        adapter.retryThresholdExceeded(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), 5, sensitive);

        assertThat(output).contains("failureCode=UNCLASSIFIED").doesNotContain(sensitive);
        assertThat(registry.get("paycore.registration.retry.threshold")
                .tag("failure_code", "UNCLASSIFIED").counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .noneSatisfy(tag -> assertThat(tag.getValue())
                        .contains("@", "bearer-secret", "partial-subject")));
    }
}
