package dev.martin.paycore.identity.infrastructure.persistence;

import dev.martin.paycore.identity.application.port.out.RegistrationWorkPort;
import dev.martin.paycore.identity.application.registration.ClaimedRegistration;
import dev.martin.paycore.identity.application.registration.ProvisionedIdentity;
import dev.martin.paycore.identity.application.registration.RegistrationOperationState;
import dev.martin.paycore.identity.application.registration.RegistrationIntegrityException;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.Email;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;

@Component
public class RegistrationWorkAdapter implements RegistrationWorkPort {

    private final JdbcClient jdbcClient;
    private final PostgresTransactionExecutor transactions;

    public RegistrationWorkAdapter(JdbcClient jdbcClient, PostgresTransactionExecutor transactions) {
        this.jdbcClient = jdbcClient;
        this.transactions = transactions;
    }

    @Override
    public Optional<ClaimedRegistration> claimNext(Instant now, Duration leaseDuration) {
        return transactions.execute(() -> claimNextInTransaction(now, leaseDuration));
    }

    private Optional<ClaimedRegistration> claimNextInTransaction(Instant now, Duration leaseDuration) {
        UUID claimToken = UUID.randomUUID();
        Optional<UUID> operationId = jdbcClient.sql("""
                        WITH candidate AS (
                            SELECT id
                            FROM registration_operations
                            WHERE state IN ('PENDING_IDENTITY', 'IDENTITY_LINKED')
                              AND next_attempt_at <= :now
                              AND (lease_until IS NULL OR lease_until <= :now)
                            ORDER BY next_attempt_at, created_at
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE registration_operations operation
                        SET claim_token = :claimToken,
                            lease_until = :leaseUntil,
                            fencing_version = operation.fencing_version + 1,
                            attempt_count = operation.attempt_count + 1,
                            updated_at = :now
                        FROM candidate
                        WHERE operation.id = candidate.id
                        RETURNING operation.id
                        """)
                .param("now", atUtc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("claimToken", claimToken)
                .param("leaseUntil", atUtc(now.plus(leaseDuration)), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(UUID.class)
                .optional();
        return operationId.map(id -> loadClaim(id, claimToken));
    }

    @Override
    public boolean renewLease(ClaimedRegistration claim, Instant now, Duration leaseDuration) {
        return transactions.execute(() -> renewLeaseInTransaction(claim, now, leaseDuration));
    }

    private boolean renewLeaseInTransaction(ClaimedRegistration claim, Instant now, Duration leaseDuration) {
        return jdbcClient.sql("""
                        UPDATE registration_operations
                        SET lease_until=:leaseUntil, updated_at=:now
                        WHERE id=:id AND state=:state AND claim_token=:claimToken
                          AND fencing_version=:fencingVersion AND lease_until > :now
                        """)
                .param("leaseUntil", atUtc(now.plus(leaseDuration)), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", atUtc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", claim.operationId())
                .param("state", claim.state().name())
                .param("claimToken", claim.claimToken())
                .param("fencingVersion", claim.fencingVersion())
                .update() == 1;
    }

    @Override
    public boolean markIdentityLinked(ClaimedRegistration claim, ProvisionedIdentity identity, Instant now) {
        return transactions.execute(() -> markIdentityLinkedInTransaction(claim, identity, now));
    }

    private boolean markIdentityLinkedInTransaction(
            ClaimedRegistration claim, ProvisionedIdentity identity, Instant now) {
        int advanced = jdbcClient.sql("""
                        UPDATE registration_operations
                        SET state='IDENTITY_LINKED', external_subject=:subject, claim_token=NULL,
                            lease_until=NULL, next_attempt_at=:now, updated_at=:now, failure_code=NULL
                        WHERE id=:id AND state='PENDING_IDENTITY' AND claim_token=:claimToken
                          AND fencing_version=:fencingVersion AND lease_until > :now
                        """)
                .param("subject", identity.subject())
                .param("now", atUtc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", claim.operationId())
                .param("claimToken", claim.claimToken())
                .param("fencingVersion", claim.fencingVersion())
                .update();
        if (advanced == 0) {
            return false;
        }
        try {
            jdbcClient.sql("""
                            INSERT INTO external_identities (issuer, subject, customer_id, created_at)
                            VALUES (:issuer, :subject, :customerId, :createdAt)
                            """)
                    .param("issuer", identity.issuer())
                    .param("subject", identity.subject())
                    .param("customerId", claim.customerId().value())
                    .param("createdAt", atUtc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                    .update();
        } catch (DataIntegrityViolationException exception) {
            throw new RegistrationIntegrityException("IDENTITY_LINK_CONFLICT", exception);
        }
        return true;
    }

    @Override
    public boolean complete(ClaimedRegistration claim, Instant now) {
        return transactions.execute(() -> completeInTransaction(claim, now));
    }

    private boolean completeInTransaction(ClaimedRegistration claim, Instant now) {
        int advanced = jdbcClient.sql("""
                        UPDATE registration_operations
                        SET state='COMPLETED', claim_token=NULL, lease_until=NULL, next_attempt_at=NULL,
                            updated_at=:now, failure_code=NULL
                        WHERE id=:id AND state='IDENTITY_LINKED' AND claim_token=:claimToken
                          AND fencing_version=:fencingVersion AND lease_until > :now
                        """)
                .param("now", atUtc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", claim.operationId())
                .param("claimToken", claim.claimToken())
                .param("fencingVersion", claim.fencingVersion())
                .update();
        if (advanced == 0) {
            return false;
        }
        int activated = jdbcClient.sql("""
                        UPDATE customers SET status='ACTIVE', updated_at=:now, version=version+1
                        WHERE id=:customerId AND status='PROVISIONING'
                        """)
                .param("now", atUtc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("customerId", claim.customerId().value())
                .update();
        if (activated != 1) {
            throw new IllegalStateException("Customer could not be activated");
        }
        return true;
    }

    @Override
    public void scheduleRetry(ClaimedRegistration claim, Instant now, Instant nextAttemptAt, String failureCode) {
        transactions.execute(() -> {
            releaseClaim(claim, now, nextAttemptAt, failureCode);
            return null;
        });
    }

    @Override
    public void requireReconciliation(ClaimedRegistration claim, String failureCode, Instant now) {
        transactions.execute(() -> {
            requireReconciliationInTransaction(claim, failureCode, now);
            return null;
        });
    }

    private void requireReconciliationInTransaction(
            ClaimedRegistration claim, String failureCode, Instant now) {
        int updated = jdbcClient.sql("""
                        UPDATE registration_operations
                        SET state='RECONCILIATION_REQUIRED', claim_token=NULL, lease_until=NULL,
                            next_attempt_at=NULL, failure_code=:failureCode, updated_at=:now
                        WHERE id=:id AND state=:state AND claim_token=:claimToken
                          AND fencing_version=:fencingVersion AND lease_until > :now
                        """)
                .param("failureCode", failureCode)
                .param("now", atUtc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("state", claim.state().name())
                .param("id", claim.operationId())
                .param("claimToken", claim.claimToken())
                .param("fencingVersion", claim.fencingVersion())
                .update();
        if (updated == 0) {
            throw new IllegalStateException("Stale claim cannot require reconciliation");
        }
    }

    private void releaseClaim(ClaimedRegistration claim, Instant now,
            Instant nextAttemptAt, String failureCode) {
        int updated = jdbcClient.sql("""
                        UPDATE registration_operations
                        SET claim_token=NULL, lease_until=NULL, next_attempt_at=:nextAttemptAt,
                            failure_code=:failureCode, updated_at=:updatedAt
                         WHERE id=:id AND state=:state AND claim_token=:claimToken
                           AND fencing_version=:fencingVersion AND lease_until > :now
                        """)
                .param("nextAttemptAt", atUtc(nextAttemptAt), Types.TIMESTAMP_WITH_TIMEZONE)
                 .param("failureCode", failureCode)
                 .param("updatedAt", atUtc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                 .param("now", atUtc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                 .param("state", claim.state().name())
                .param("id", claim.operationId())
                .param("claimToken", claim.claimToken())
                .param("fencingVersion", claim.fencingVersion())
                .update();
        if (updated == 0) {
            throw new IllegalStateException("Stale claim cannot schedule retry");
        }
    }

    private ClaimedRegistration loadClaim(UUID operationId, UUID claimToken) {
        return jdbcClient.sql("""
                        SELECT operation.id, operation.state, operation.external_subject,
                               operation.fencing_version, operation.attempt_count, operation.lease_until,
                               customer.id AS customer_id, customer.email
                        FROM registration_operations operation
                        JOIN customers customer ON customer.id=operation.customer_id
                        WHERE operation.id=:id AND operation.claim_token=:claimToken
                        """)
                .param("id", operationId)
                .param("claimToken", claimToken)
                .query((row, number) -> new ClaimedRegistration(
                        row.getObject("id", UUID.class),
                        new CustomerId(row.getObject("customer_id", UUID.class)),
                        Email.of(row.getString("email")),
                        RegistrationOperationState.valueOf(row.getString("state")),
                        row.getString("external_subject"), claimToken,
                        row.getLong("fencing_version"), row.getInt("attempt_count"),
                        row.getObject("lease_until", OffsetDateTime.class).toInstant()))
                .single();
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
