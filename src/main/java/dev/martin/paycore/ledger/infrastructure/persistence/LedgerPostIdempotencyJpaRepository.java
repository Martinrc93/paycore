package dev.martin.paycore.ledger.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LedgerPostIdempotencyJpaRepository extends JpaRepository<LedgerPostIdempotencyEntity, String> {

    @Modifying
    @Query(value = """
            INSERT INTO ledger_posting_idempotency
                (idempotency_key, request_fingerprint, transaction_id, created_at)
            VALUES (:key, :fingerprint, NULL, :createdAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claim(
            @Param("key") String key,
            @Param("fingerprint") String fingerprint,
            @Param("createdAt") java.time.Instant createdAt);

    @Modifying
    @Query(value = """
            UPDATE ledger_posting_idempotency
               SET transaction_id = :transactionId
             WHERE idempotency_key = :key
            """, nativeQuery = true)
    int complete(@Param("key") String key, @Param("transactionId") java.util.UUID transactionId);

    @Query("select e from LedgerPostIdempotencyEntity e where e.idempotencyKey = :key")
    Optional<LedgerPostIdempotencyEntity> findByKey(@Param("key") String key);
}
