package dev.martin.paycore.identity.application.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RegistrationBackoffTest {

    @Test
    void appliesBoundedJitterToExponentialDelay() {
        RegistrationBackoff low = new RegistrationBackoff(
                Duration.ofSeconds(5), Duration.ofHours(1), () -> 0.0);
        RegistrationBackoff neutral = new RegistrationBackoff(
                Duration.ofSeconds(5), Duration.ofHours(1), () -> 0.5);

        assertThat(low.delayForAttempt(4)).isEqualTo(Duration.ofSeconds(20));
        assertThat(neutral.delayForAttempt(4)).isEqualTo(Duration.ofSeconds(40));
        assertThat(neutral.delayForAttempt(30)).isEqualTo(Duration.ofHours(1));
    }
}
