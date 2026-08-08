package dev.martin.paycore.identity.application.registration;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

public final class RegistrationBackoff {

    private final Duration base;
    private final Duration maximum;
    private final DoubleSupplier jitter;

    public RegistrationBackoff(Duration base, Duration maximum) {
        this(base, maximum, () -> ThreadLocalRandom.current().nextDouble());
    }

    public RegistrationBackoff(Duration base, Duration maximum, DoubleSupplier jitter) {
        this.base = Objects.requireNonNull(base, "base");
        this.maximum = Objects.requireNonNull(maximum, "maximum");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        if (base.isNegative() || base.isZero() || base.toMillis() < 1 || maximum.compareTo(base) < 0) {
            throw new IllegalArgumentException("Invalid backoff durations");
        }
    }

    public Duration delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        long multiplier = 1L << Math.min(attempt - 1, 30);
        try {
            Duration delay = base.multipliedBy(multiplier);
            double sample = jitter.getAsDouble();
            if (sample < 0.0 || sample >= 1.0) {
                throw new IllegalStateException("jitter must be in [0, 1)");
            }
            long jitteredMillis = Math.max(1, Math.round(delay.toMillis() * (0.5 + sample)));
            Duration jittered = Duration.ofMillis(jitteredMillis);
            return jittered.compareTo(maximum) > 0 ? maximum : jittered;
        } catch (ArithmeticException exception) {
            return maximum;
        }
    }

    public Duration cap(Duration requested) {
        Objects.requireNonNull(requested, "requested");
        return requested.compareTo(maximum) > 0 ? maximum : requested;
    }
}
