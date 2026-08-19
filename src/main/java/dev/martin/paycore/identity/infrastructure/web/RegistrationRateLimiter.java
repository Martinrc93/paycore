package dev.martin.paycore.identity.infrastructure.web;

import dev.martin.paycore.identity.domain.model.Email;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;

public class RegistrationRateLimiter {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long RETRY_AFTER_SECONDS = 60;
    private final JdbcClient jdbcClient;
    private final byte[] secret;
    private final Clock clock;
    private final int sourceLimit;
    private final int emailLimit;

    public RegistrationRateLimiter(JdbcClient jdbcClient, String secret, Clock clock,
            int sourceLimit, int emailLimit) {
        this.jdbcClient = jdbcClient;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.sourceLimit = sourceLimit;
        this.emailLimit = emailLimit;
        if (this.secret.length < 32 || sourceLimit < 1 || emailLimit < 1) {
            throw new IllegalArgumentException("Invalid registration rate-limit configuration");
        }
    }

    public void check(String source, Email email) {
        Instant now = clock.instant();
        Instant windowStart = now.truncatedTo(ChronoUnit.MINUTES);
        Instant expiresAt = windowStart.plus(2, ChronoUnit.MINUTES);
        incrementAndCheck("source:" + source, windowStart, expiresAt, sourceLimit);
        incrementAndCheck("email:" + email.value(), windowStart, expiresAt, emailLimit);
    }

    private void incrementAndCheck(String rawBucket, Instant windowStart, Instant expiresAt,
            int limit) {
        int attempts = jdbcClient.sql("""
                        INSERT INTO registration_rate_limits (bucket_key, window_start, attempts, expires_at)
                        VALUES (:bucketKey, :windowStart, 1, :expiresAt)
                        ON CONFLICT (bucket_key, window_start)
                        DO UPDATE SET attempts=registration_rate_limits.attempts+1,
                                      expires_at=EXCLUDED.expires_at
                        RETURNING attempts
                        """)
                .param("bucketKey", digest(rawBucket))
                .param("windowStart", atUtc(windowStart), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiresAt", atUtc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Integer.class)
                .single();
        if (attempts > limit) {
            throw new RateLimitExceededException(RETRY_AFTER_SECONDS);
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
