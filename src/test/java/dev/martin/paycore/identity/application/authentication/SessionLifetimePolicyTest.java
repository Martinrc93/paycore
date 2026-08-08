package dev.martin.paycore.identity.application.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionLifetimePolicyTest {

    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-08-08T10:00:00Z");

    @Test
    void expiresExactlyEightHoursAfterSuccessfulAuthentication() {
        SessionLifetimePolicy policy = policyAt(AUTHENTICATED_AT.plus(Duration.ofHours(7)));

        assertThat(policy.absoluteExpiry(AUTHENTICATED_AT))
                .isEqualTo(Instant.parse("2026-08-08T18:00:00Z"));
    }

    @Test
    void allowsThirtyIdleMinutesWhileMoreAbsoluteLifetimeRemains() {
        SessionLifetimePolicy policy = policyAt(AUTHENTICATED_AT.plus(Duration.ofHours(2)));

        assertThat(policy.remainingIdleTimeout(AUTHENTICATED_AT)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void capsIdleTimeoutToTheRemainingAbsoluteLifetime() {
        SessionLifetimePolicy policy = policyAt(
                AUTHENTICATED_AT.plus(Duration.ofHours(7)).plus(Duration.ofMinutes(50)));

        assertThat(policy.remainingIdleTimeout(AUTHENTICATED_AT)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void reportsNoIdleTimeAtOrAfterAbsoluteExpiry() {
        assertThat(policyAt(AUTHENTICATED_AT.plus(Duration.ofHours(8)))
                .remainingIdleTimeout(AUTHENTICATED_AT)).isZero();
        assertThat(policyAt(AUTHENTICATED_AT.plus(Duration.ofHours(9)))
                .remainingIdleTimeout(AUTHENTICATED_AT)).isZero();
    }

    private static SessionLifetimePolicy policyAt(Instant now) {
        return new SessionLifetimePolicy(Clock.fixed(now, ZoneOffset.UTC));
    }
}
