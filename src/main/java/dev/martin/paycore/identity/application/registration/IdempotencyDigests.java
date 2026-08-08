package dev.martin.paycore.identity.application.registration;

import java.util.List;
import java.util.Objects;

public record IdempotencyDigests(VersionedDigest primary, List<VersionedDigest> candidates) {

    public IdempotencyDigests {
        Objects.requireNonNull(primary, "primary");
        candidates = List.copyOf(candidates);
        if (candidates.isEmpty() || !candidates.contains(primary)) {
            throw new IllegalArgumentException("candidates must contain primary digest");
        }
    }
}
