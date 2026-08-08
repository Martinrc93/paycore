package dev.martin.paycore.identity.infrastructure.persistence;

import dev.martin.paycore.identity.application.port.out.RegistrationAcceptancePort;
import dev.martin.paycore.identity.application.registration.RegistrationAcceptanceResult;
import dev.martin.paycore.identity.application.registration.RegistrationIntent;
import dev.martin.paycore.identity.application.registration.VersionedDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RegistrationAcceptanceAdapter implements RegistrationAcceptancePort {

    private final JdbcClient jdbcClient;
    private final PostgresTransactionExecutor transactions;

    public RegistrationAcceptanceAdapter(JdbcClient jdbcClient, PostgresTransactionExecutor transactions) {
        this.jdbcClient = jdbcClient;
        this.transactions = transactions;
    }

    @Override
    public RegistrationAcceptanceResult accept(RegistrationIntent intent) {
        return transactions.execute(() -> acceptInTransaction(intent));
    }

    private RegistrationAcceptanceResult acceptInTransaction(RegistrationIntent intent) {
        List<String> candidateReferences = intent.idempotencyDigests().candidates().stream()
                .map(VersionedDigest::reference)
                .sorted()
                .toList();

        lockIdempotencyReferences(candidateReferences);
        Optional<String> existingFingerprint = findFingerprint(candidateReferences, intent.customer().createdAt());
        if (existingFingerprint.isPresent()) {
            return existingFingerprint.get().equals(intent.requestFingerprint())
                    ? RegistrationAcceptanceResult.ACCEPTED
                    : RegistrationAcceptanceResult.CONFLICT;
        }

        boolean customerCreated = insertCustomer(intent) == 1;
        String state = customerCreated ? "PENDING_IDENTITY" : "DUPLICATE_SUPPRESSED";
        UUID customerId = customerCreated ? intent.customer().id().value() : null;
        insertOperation(intent, state, customerId);
        return RegistrationAcceptanceResult.ACCEPTED;
    }

    private void lockIdempotencyReferences(List<String> keyReferences) {
        for (String keyReference : keyReferences) {
            jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:reference, 0))")
                    .param("reference", keyReference)
                    .query()
                    .singleRow();
        }
    }

    private Optional<String> findFingerprint(List<String> keyReferences, Instant retainedAfter) {
        return jdbcClient.sql("""
                        SELECT request_fingerprint
                        FROM registration_operations
                        WHERE key_reference IN (:references)
                          AND expires_at > :retainedAfter
                        ORDER BY created_at DESC
                        LIMIT 1
                        """)
                .param("references", keyReferences)
                .param("retainedAfter", atUtc(retainedAfter), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .query(String.class)
                .optional();
    }

    private int insertCustomer(RegistrationIntent intent) {
        return jdbcClient.sql("""
                        INSERT INTO customers (
                            id, email, customer_type, status, created_at, updated_at, version
                        ) VALUES (
                            :id, :email, :type, :status, :createdAt, :updatedAt, 0
                        ) ON CONFLICT (email) DO NOTHING
                        """)
                .param("id", intent.customer().id().value())
                .param("email", intent.customer().email().value())
                .param("type", intent.customer().type().name())
                .param("status", intent.customer().status().name())
                .param("createdAt", atUtc(intent.customer().createdAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", atUtc(intent.customer().updatedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private int insertOperation(RegistrationIntent intent, String state, UUID customerId) {
        VersionedDigest primary = intent.idempotencyDigests().primary();
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        INSERT INTO registration_operations (
                            id, key_reference, key_digest_version, key_digest, request_fingerprint,
                            customer_id, state, created_at, updated_at, expires_at, next_attempt_at
                        ) VALUES (
                            :id, :keyReference, :keyVersion, :keyDigest, :fingerprint,
                            :customerId, :state, :createdAt, :updatedAt, :expiresAt, :nextAttemptAt
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("keyReference", primary.reference())
                .param("keyVersion", primary.version())
                .param("keyDigest", primary.digest())
                .param("fingerprint", intent.requestFingerprint())
                .param("state", state)
                .param("createdAt", atUtc(intent.customer().createdAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", atUtc(intent.customer().updatedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiresAt", atUtc(intent.expiresAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("nextAttemptAt", "PENDING_IDENTITY".equals(state)
                                ? atUtc(intent.customer().createdAt()) : null,
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        if (customerId == null) {
            statement = statement.param("customerId", null, java.sql.Types.OTHER);
        } else {
            statement = statement.param("customerId", customerId);
        }
        return statement.update();
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
