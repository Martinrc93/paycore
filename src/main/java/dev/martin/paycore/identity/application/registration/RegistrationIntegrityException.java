package dev.martin.paycore.identity.application.registration;

import java.util.Objects;

public final class RegistrationIntegrityException extends RuntimeException {

    private final String code;

    public RegistrationIntegrityException(String code) {
        super(code);
        this.code = Objects.requireNonNull(code, "code");
    }

    public RegistrationIntegrityException(String code, Throwable cause) {
        super(code, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
