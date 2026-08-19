package dev.martin.paycore.ledger.application.balance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.ledger.application.port.out.LedgerBalanceStore;
import dev.martin.paycore.ledger.domain.model.LedgerAccountBalance;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerBalanceServicesTest {

    private final UUID accountId = UUID.randomUUID();
    private final InMemoryBalances balances = new InMemoryBalances();

    @Test
    void queriesTheProjectedAccountBalanceThroughTheApplicationPort() {
        LedgerAccountBalance projected = new LedgerAccountBalance(
                LedgerAccountType.ASSET, new BigDecimal("12.00"), new BigDecimal("3.00"));
        balances.projected = projected;

        LedgerAccountBalance result = new QueryLedgerBalancesService(balances)
                .find(new LedgerBalanceQuery(accountId));

        assertThat(result).isEqualTo(projected);
    }

    @Test
    void locksBothProjectedBalancesThroughTheApplicationPort() {
        LedgerAccountBalance projected = new LedgerAccountBalance(
                LedgerAccountType.ASSET, new BigDecimal("12.00"), new BigDecimal("3.00"));
        balances.projected = projected;

        LedgerAccountBalancePair result = new QueryLedgerBalancesService(balances)
                .findPairForUpdate(accountId, UUID.randomUUID());

        assertThat(result.available()).isEqualTo(projected);
        assertThat(balances.pairLocked).isTrue();
    }

    @Test
    void reconcilesThroughTheApplicationPort() {
        LedgerReconciliationResult expected = new LedgerReconciliationResult(
                accountId,
                new BigDecimal("12.00"),
                new BigDecimal("3.00"),
                new BigDecimal("12.00"),
                new BigDecimal("3.00"),
                true);
        balances.reconciliation = expected;

        LedgerReconciliationResult result = new ReconcileLedgerBalancesService(balances)
                .reconcile(accountId);

        assertThat(result).isEqualTo(expected);
        assertThat(balances.reconciledAccount).isEqualTo(accountId);
    }

    @Test
    void rebuildsThroughTheApplicationPort() {
        LedgerReconciliationResult expected = new LedgerReconciliationResult(
                accountId,
                new BigDecimal("12.00"),
                new BigDecimal("3.00"),
                new BigDecimal("12.00"),
                new BigDecimal("3.00"),
                true);
        balances.reconciliation = expected;

        LedgerReconciliationResult result = new RebuildLedgerBalancesService(balances)
                .rebuild(accountId);

        assertThat(result).isEqualTo(expected);
        assertThat(balances.rebuiltAccount).isEqualTo(accountId);
    }

    private static final class InMemoryBalances implements LedgerBalanceStore {

        private LedgerAccountBalance projected;
        private LedgerReconciliationResult reconciliation;
        private UUID reconciledAccount;
        private UUID rebuiltAccount;
        private boolean pairLocked;

        @Override
        public void initialize(UUID accountId) {
        }

        @Override
        public LedgerAccountBalance find(LedgerBalanceQuery query) {
            return projected;
        }

        @Override
        public LedgerAccountBalancePair findPair(java.util.Collection<UUID> accountIds) {
            return new LedgerAccountBalancePair(projected, projected);
        }

        @Override
        public LedgerAccountBalancePair findPairForUpdate(java.util.Collection<UUID> accountIds) {
            pairLocked = true;
            return findPair(accountIds);
        }

        @Override
        public LedgerReconciliationResult reconcile(UUID accountId) {
            reconciledAccount = accountId;
            return reconciliation;
        }

        @Override
        public LedgerReconciliationResult rebuild(UUID accountId) {
            rebuiltAccount = accountId;
            return reconciliation;
        }

        @Override
        public void lockAndValidate(dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction) {
        }

        @Override
        public void apply(dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction) {
        }
    }
}
