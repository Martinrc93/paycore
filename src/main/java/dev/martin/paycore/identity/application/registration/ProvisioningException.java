package dev.martin.paycore.identity.application.registration;

import java.util.Objects;
import java.time.Duration;
import java.util.Optional;

public final class ProvisioningException extends RuntimeException {

    private final ProvisioningFailure failure;
    private final String code;
    private final Duration retryAfter;

    public ProvisioningException(ProvisioningFailure failure, String code) {
        this(failure, code, null, null);
    }

    public ProvisioningException(ProvisioningFailure failure, String code, Throwable cause) {
        this(failure, code, null, cause);
    }

    public ProvisioningException(ProvisioningFailure failure, String code, Duration retryAfter, Throwable cause) {
        super(code, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
        this.code = Objects.requireNonNull(code, "code");
        this.retryAfter = retryAfter;
        if (retryAfter != null && (retryAfter.isNegative() || retryAfter.isZero())) {
            throw new IllegalArgumentException("retryAfter must be positive");
        }
    }

    public ProvisioningFailure failure() {
        return failure;
    }

    public String code() {
        return code;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
