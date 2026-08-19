package dev.martin.paycore.ledger.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LedgerBalanceJpaRepository extends JpaRepository<LedgerBalanceEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO ledger_account_balances (account_id)
            SELECT a.id
              FROM ledger_accounts a
             WHERE a.id IN (:accountIds)
               AND NOT EXISTS (
                   SELECT 1
                     FROM ledger_transaction_lines l
                    WHERE l.account_id = a.id
               )
             ORDER BY a.id
            ON CONFLICT (account_id) DO NOTHING
            """, nativeQuery = true)
    int initializeForAccounts(@Param("accountIds") Collection<UUID> accountIds);

    @Query(value = """
            SELECT b.*
              FROM ledger_account_balances b
             WHERE b.account_id IN (:accountIds)
             ORDER BY b.account_id
             FOR UPDATE
            """, nativeQuery = true)
    List<LedgerBalanceEntity> findAllByAccountIdsForUpdate(@Param("accountIds") Collection<UUID> accountIds);

    @Query(value = """
            SELECT b.*
              FROM ledger_account_balances b
             WHERE b.account_id = :accountId
             FOR UPDATE
            """, nativeQuery = true)
    Optional<LedgerBalanceEntity> findByAccountIdForUpdate(@Param("accountId") UUID accountId);

    @Query(value = """
            SELECT l.account_id AS accountId,
                   COALESCE(SUM(l.amount) FILTER (WHERE l.direction = 'DEBIT'), 0) AS debits,
                   COALESCE(SUM(l.amount) FILTER (WHERE l.direction = 'CREDIT'), 0) AS credits
              FROM ledger_transaction_lines l
              JOIN ledger_transactions t ON t.id = l.transaction_id
             WHERE l.account_id = :accountId
             GROUP BY l.account_id
            """, nativeQuery = true)
    Optional<ConfirmedLineTotals> aggregateConfirmedLines(@Param("accountId") UUID accountId);

    @Query(value = """
            SELECT a.id AS accountId,
                   a.account_type AS accountType,
                   a.status AS accountStatus,
                   a.currency AS currency,
                   a.balance_policy AS balancePolicy,
                   b.cumulative_debits AS cumulativeDebits,
                   b.cumulative_credits AS cumulativeCredits,
                   b.consistency_status AS consistencyStatus
              FROM ledger_accounts a
              JOIN ledger_account_balances b ON b.account_id = a.id
             WHERE a.id IN (:accountIds)
             ORDER BY a.id
            """, nativeQuery = true)
    List<BalancePairProjection> findPair(@Param("accountIds") Collection<UUID> accountIds);

    @Query(value = """
            SELECT a.id AS accountId,
                   a.account_type AS accountType,
                   a.status AS accountStatus,
                   a.currency AS currency,
                   a.balance_policy AS balancePolicy,
                   b.cumulative_debits AS cumulativeDebits,
                   b.cumulative_credits AS cumulativeCredits,
                   b.consistency_status AS consistencyStatus
              FROM ledger_accounts a
              JOIN ledger_account_balances b ON b.account_id = a.id
             WHERE a.id IN (:accountIds)
             ORDER BY b.account_id
             FOR UPDATE OF b
            """, nativeQuery = true)
    List<BalancePairProjection> findPairForUpdate(@Param("accountIds") Collection<UUID> accountIds);

    @Modifying
    @Query(value = """
            UPDATE ledger_account_balances
               SET cumulative_debits = cumulative_debits + :debits,
                   cumulative_credits = cumulative_credits + :credits,
                   updated_at = CURRENT_TIMESTAMP
             WHERE account_id = :accountId
            """, nativeQuery = true)
    int increment(
            @Param("accountId") UUID accountId,
            @Param("debits") BigDecimal debits,
            @Param("credits") BigDecimal credits);

    @Modifying
    @Query(value = """
            UPDATE ledger_account_balances
               SET cumulative_debits = :debits,
                   cumulative_credits = :credits,
                   consistency_status = :consistencyStatus,
                   updated_at = CURRENT_TIMESTAMP
             WHERE account_id = :accountId
            """, nativeQuery = true)
    int replace(
            @Param("accountId") UUID accountId,
            @Param("debits") BigDecimal debits,
            @Param("credits") BigDecimal credits,
            @Param("consistencyStatus") String consistencyStatus);

    interface ConfirmedLineTotals {

        UUID getAccountId();

        BigDecimal getDebits();

        BigDecimal getCredits();
    }

    interface BalancePairProjection {

        UUID getAccountId();

        String getAccountType();

        String getAccountStatus();

        String getCurrency();

        String getBalancePolicy();

        BigDecimal getCumulativeDebits();

        BigDecimal getCumulativeCredits();

        String getConsistencyStatus();
    }
}
