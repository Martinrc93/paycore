package dev.martin.paycore.wallet.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WalletJpaRepository extends JpaRepository<WalletEntity, UUID> {

    Optional<WalletEntity> findByCustomerId(UUID customerId);

    @Modifying
    @Query(value = """
            INSERT INTO wallets (
                id, customer_id, currency, available_account_id, reserved_account_id,
                status, created_at, updated_at, version
            ) VALUES (
                :id, :customerId, :currency, :availableAccountId, :reservedAccountId,
                :status, :createdAt, :updatedAt, 0
            ) ON CONFLICT (customer_id) DO NOTHING
            """, nativeQuery = true)
    int claim(
            @Param("id") UUID id,
            @Param("customerId") UUID customerId,
            @Param("currency") String currency,
            @Param("availableAccountId") UUID availableAccountId,
            @Param("reservedAccountId") UUID reservedAccountId,
            @Param("status") String status,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);
}
