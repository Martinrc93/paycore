package dev.martin.paycore.identity.application.registration;

import dev.martin.paycore.identity.domain.model.CustomerType;
import dev.martin.paycore.identity.domain.model.Email;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class IdempotencyDigester {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final int currentVersion;
    private final Map<Integer, byte[]> secrets;

    public IdempotencyDigester(int currentVersion, Map<Integer, byte[]> secrets) {
        this.currentVersion = currentVersion;
        this.secrets = copySecrets(secrets);
        if (!this.secrets.containsKey(currentVersion)) {
            throw new IllegalArgumentException("Current digest secret is missing");
        }
    }

    public IdempotencyDigests digest(String rawKey) {
        Objects.requireNonNull(rawKey, "rawKey");
        byte[] keyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 1 || keyBytes.length > 128) {
            throw new IllegalArgumentException("Idempotency key must be 1-128 bytes");
        }

        List<VersionedDigest> candidates = new ArrayList<>();
        candidates.add(digest(currentVersion, keyBytes));
        secrets.keySet().stream()
                .filter(version -> version != currentVersion)
                .sorted(java.util.Comparator.reverseOrder())
                .map(version -> digest(version, keyBytes))
                .forEach(candidates::add);
        return new IdempotencyDigests(candidates.getFirst(), candidates);
    }

    public String fingerprint(Email email, CustomerType type) {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(type, "type");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] input = (email.value() + "\n" + type.name()).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private VersionedDigest digest(int version, byte[] rawKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secrets.get(version), HMAC_ALGORITHM));
            return new VersionedDigest(version, HexFormat.of().formatHex(mac.doFinal(rawKey)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static Map<Integer, byte[]> copySecrets(Map<Integer, byte[]> source) {
        Objects.requireNonNull(source, "secrets");
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        source.forEach((version, secret) -> {
            if (version == null || version < 1 || secret == null || secret.length < 32) {
                throw new IllegalArgumentException("Digest secrets must be versioned and at least 32 bytes");
            }
            result.put(version, secret.clone());
        });
        return Map.copyOf(result);
    }
}
