package dev.martin.paycore.wallet.infrastructure.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.ledger.application.balance.LedgerAccountBalancePair;
import dev.martin.paycore.ledger.application.balance.QueryLedgerBalancesService;
import dev.martin.paycore.ledger.application.port.out.LedgerBalanceStore;
import dev.martin.paycore.ledger.domain.model.LedgerAccountBalance;
import dev.martin.paycore.ledger.domain.model.LedgerAccountStatus;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import dev.martin.paycore.wallet.application.port.out.WalletBalances;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DataAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WalletBalanceReaderAdapterTest {

    @Test
    void readsBothWalletBalancesThroughOneLedgerApplicationOperation() {
        UUID available = UUID.randomUUID();
        UUID reserved = UUID.randomUUID();
        AtomicInteger pairReads = new AtomicInteger();
        LedgerBalanceStore store = new LedgerBalanceStore() {
            @Override
            public LedgerAccountBalancePair findPair(Collection<UUID> accountIds) {
                pairReads.incrementAndGet();
                return new LedgerAccountBalancePair(
                        new LedgerAccountBalance(LedgerAccountType.LIABILITY,
                                BigDecimal.ZERO, new BigDecimal("12.00")),
                        new LedgerAccountBalance(LedgerAccountType.LIABILITY,
                                BigDecimal.ZERO, new BigDecimal("3.00")));
            }

            @Override public void initialize(UUID accountId) { }
            @Override public LedgerAccountBalance find(dev.martin.paycore.ledger.application.balance.LedgerBalanceQuery query) { throw new AssertionError("single read"); }
            @Override public LedgerAccountBalancePair findPairForUpdate(Collection<UUID> accountIds) { throw new UnsupportedOperationException(); }
            @Override public dev.martin.paycore.ledger.application.balance.LedgerReconciliationResult reconcile(UUID accountId) { throw new UnsupportedOperationException(); }
            @Override public dev.martin.paycore.ledger.application.balance.LedgerReconciliationResult rebuild(UUID accountId) { throw new UnsupportedOperationException(); }
            @Override public void lockAndValidate(dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction) { throw new UnsupportedOperationException(); }
            @Override public void apply(dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction) { throw new UnsupportedOperationException(); }
        };

        WalletBalances result = new WalletBalanceReaderAdapter(
                new QueryLedgerBalancesService(store)).read(available, reserved);

        assertThat(pairReads).hasValue(1);
        assertThat(result.available()).isEqualByComparingTo("12.00");
        assertThat(result.reserved()).isEqualByComparingTo("3.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"40001", "40P01"})
    void propagatesRetryableDataAccessExceptionFromLedgerBalanceQueries(String sqlState) {
        DataAccessException retryable = new DataAccessException(
                "retryable database failure", new SQLException("retry", sqlState)) { };
        LedgerBalanceStore store = storeThatThrows(retryable);
        WalletBalanceReaderAdapter reader = new WalletBalanceReaderAdapter(
                new QueryLedgerBalancesService(store));

        assertThatThrownBy(() -> reader.read(UUID.randomUUID(), UUID.randomUUID()))
                .isSameAs(retryable);
        assertThatThrownBy(() -> reader.readForUpdate(UUID.randomUUID(), UUID.randomUUID()))
                .isSameAs(retryable);
    }

    @Test
    void rejectsAClosedWalletAccountEvenWhenItsProjectionIsConsistent() {
        UUID available = UUID.randomUUID();
        UUID reserved = UUID.randomUUID();
        LedgerBalanceStore store = storeWith(
                new LedgerAccountBalance(LedgerAccountType.LIABILITY, LedgerAccountStatus.CLOSED,
                        CurrencyCode.USD, LedgerBalancePolicy.NON_NEGATIVE,
                        BigDecimal.ZERO, new BigDecimal("12.00")),
                new LedgerAccountBalance(LedgerAccountType.LIABILITY, LedgerAccountStatus.OPEN,
                        CurrencyCode.USD, LedgerBalancePolicy.NON_NEGATIVE,
                        BigDecimal.ZERO, BigDecimal.ZERO));

        assertThatThrownBy(() -> new WalletBalanceReaderAdapter(
                new QueryLedgerBalancesService(store)).read(available, reserved))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Wallet balance is inconsistent");
    }

    @Test
    void readsABlockedWalletPairForCloseWhileKeepingTheBalanceLock() {
        UUID available = UUID.randomUUID();
        UUID reserved = UUID.randomUUID();
        LedgerBalanceStore store = storeWith(
                new LedgerAccountBalance(LedgerAccountType.LIABILITY, LedgerAccountStatus.BLOCKED,
                        CurrencyCode.USD, LedgerBalancePolicy.NON_NEGATIVE,
                        BigDecimal.ZERO, new BigDecimal("12.00")),
                new LedgerAccountBalance(LedgerAccountType.LIABILITY, LedgerAccountStatus.BLOCKED,
                        CurrencyCode.USD, LedgerBalancePolicy.NON_NEGATIVE,
                        BigDecimal.ZERO, new BigDecimal("3.00")));

        WalletBalances result = new WalletBalanceReaderAdapter(
                new QueryLedgerBalancesService(store)).readForUpdateForClose(available, reserved);

        assertThat(result.total()).isEqualByComparingTo("15.00");
    }

    @Test
    void rejectsAnAccountWithWalletIncompatibleCurrencyOrPolicy() {
        UUID available = UUID.randomUUID();
        UUID reserved = UUID.randomUUID();
        LedgerBalanceStore store = storeWith(
                new LedgerAccountBalance(LedgerAccountType.LIABILITY, LedgerAccountStatus.OPEN,
                        CurrencyCode.EUR, LedgerBalancePolicy.NON_NEGATIVE,
                        BigDecimal.ZERO, new BigDecimal("12.00")),
                new LedgerAccountBalance(LedgerAccountType.LIABILITY, LedgerAccountStatus.OPEN,
                        CurrencyCode.USD, LedgerBalancePolicy.ALLOW_NEGATIVE,
                        BigDecimal.ZERO, BigDecimal.ZERO));

        assertThatThrownBy(() -> new WalletBalanceReaderAdapter(
                new QueryLedgerBalancesService(store)).read(available, reserved))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Wallet balance is inconsistent");
    }

    private static LedgerBalanceStore storeWith(LedgerAccountBalance available, LedgerAccountBalance reserved) {
        return new LedgerBalanceStore() {
            @Override
            public LedgerAccountBalancePair findPair(Collection<UUID> accountIds) {
                return new LedgerAccountBalancePair(available, reserved);
            }

            @Override public void initialize(UUID accountId) { }
            @Override public LedgerAccountBalance find(dev.martin.paycore.ledger.application.balance.LedgerBalanceQuery query) { throw new UnsupportedOperationException(); }
            @Override public LedgerAccountBalancePair findPairForUpdate(Collection<UUID> accountIds) {
                return new LedgerAccountBalancePair(available, reserved);
            }
            @Override public dev.martin.paycore.ledger.application.balance.LedgerReconciliationResult reconcile(UUID accountId) { throw new UnsupportedOperationException(); }
            @Override public dev.martin.paycore.ledger.application.balance.LedgerReconciliationResult rebuild(UUID accountId) { throw new UnsupportedOperationException(); }
            @Override public void lockAndValidate(dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction) { throw new UnsupportedOperationException(); }
            @Override public void apply(dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction) { throw new UnsupportedOperationException(); }
        };
    }

    private static LedgerBalanceStore storeThatThrows(DataAccessException failure) {
        return new LedgerBalanceStore() {
            @Override
            public LedgerAccountBalancePair findPair(Collection<UUID> accountIds) {
                throw failure;
            }

            @Override public void initialize(UUID accountId) { }
            @Override public LedgerAccountBalance find(dev.martin.paycore.ledger.application.balance.LedgerBalanceQuery query) { throw new UnsupportedOperationException(); }
            @Override public LedgerAccountBalancePair findPairForUpdate(Collection<UUID> accountIds) { throw failure; }
            @Override public dev.martin.paycore.ledger.application.balance.LedgerReconciliationResult reconcile(UUID accountId) { throw new UnsupportedOperationException(); }
            @Override public dev.martin.paycore.ledger.application.balance.LedgerReconciliationResult rebuild(UUID accountId) { throw new UnsupportedOperationException(); }
            @Override public void lockAndValidate(dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction) { throw new UnsupportedOperationException(); }
            @Override public void apply(dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction) { throw new UnsupportedOperationException(); }
        };
    }
}
