package dev.martin.paycore.identity.application.authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class SessionLifetimePolicy {

    private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(8);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final Clock clock;

    public SessionLifetimePolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Instant absoluteExpiry(Instant authenticatedAt) {
        return Objects.requireNonNull(authenticatedAt, "authenticatedAt").plus(ABSOLUTE_LIFETIME);
    }

    public Duration remainingIdleTimeout(Instant authenticatedAt) {
        Duration remaining = Duration.between(clock.instant(), absoluteExpiry(authenticatedAt));
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ZERO;
        }
        return remaining.compareTo(IDLE_TIMEOUT) < 0 ? remaining : IDLE_TIMEOUT;
    }
}
