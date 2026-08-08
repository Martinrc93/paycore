package dev.martin.paycore.identity.application.registration;

import java.util.Objects;

public record VersionedDigest(int version, String digest) {

    public VersionedDigest {
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        Objects.requireNonNull(digest, "digest");
    }

    public String reference() {
        return version + ":" + digest;
    }
}
