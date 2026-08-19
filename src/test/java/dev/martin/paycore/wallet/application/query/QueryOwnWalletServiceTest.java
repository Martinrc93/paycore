package dev.martin.paycore.wallet.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.wallet.application.port.out.WalletBalanceReader;
import dev.martin.paycore.wallet.application.port.out.WalletBalances;
import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.domain.model.Wallet;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import dev.martin.paycore.wallet.domain.model.WalletStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;

class QueryOwnWalletServiceTest {

    @Test
    void returnsAvailableReservedAndTotalWithoutLedgerAccountIds() {
        UUID customerId = UUID.randomUUID();
        UUID available = UUID.randomUUID();
        UUID reserved = UUID.randomUUID();
        Wallet wallet = Wallet.unfunded(
                dev.martin.paycore.wallet.domain.model.WalletId.newId(), customerId, available, reserved,
                Instant.parse("2026-08-17T12:00:00Z"));
        InMemoryWallets wallets = new InMemoryWallets(wallet);
        InMemoryBalances balances = new InMemoryBalances(Map.of(
                available, new WalletBalances(new BigDecimal("12.00"), BigDecimal.ZERO),
                reserved, new WalletBalances(BigDecimal.ZERO, new BigDecimal("3.00"))));

        WalletView view = new QueryOwnWalletService(wallets, balances).query(customerId);

        assertThat(view.walletId()).isEqualTo(wallet.id());
        assertThat(view.status()).isEqualTo(WalletStatus.UNFUNDED);
        assertThat(view.currency()).isEqualTo(WalletCurrency.USD);
        assertThat(view.availableBalance()).isEqualByComparingTo("12.00");
        assertThat(view.reservedBalance()).isEqualByComparingTo("3.00");
        assertThat(view.totalBalance()).isEqualByComparingTo("15.00");
        assertThat(view.toString()).doesNotContain(available.toString(), reserved.toString());
    }

    @Test
    void confirmsOnlyACompleteUsdWalletWithValidBalances() {
        UUID customerId = UUID.randomUUID();
        UUID available = UUID.randomUUID();
        UUID reserved = UUID.randomUUID();
        Wallet wallet = Wallet.unfunded(
                dev.martin.paycore.wallet.domain.model.WalletId.newId(), customerId, available, reserved,
                Instant.parse("2026-08-17T12:00:00Z"));
        QueryOwnWalletService service = new QueryOwnWalletService(
                new InMemoryWallets(wallet), new InMemoryBalances(Map.of(
                        available, new WalletBalances(BigDecimal.ZERO, BigDecimal.ZERO),
                        reserved, new WalletBalances(BigDecimal.ZERO, BigDecimal.ZERO))));

        assertThat(service.confirmCompleteUsdWallet(customerId)).isPresent();
        assertThat(service.confirmCompleteUsdWallet(UUID.randomUUID())).isEmpty();
    }

    @Test
    void doesNotConfirmAClosedWalletAsCompleteForActiveCustomerAccess() {
        UUID customerId = UUID.randomUUID();
        UUID available = UUID.randomUUID();
        UUID reserved = UUID.randomUUID();
        Wallet wallet = Wallet.unfunded(
                dev.martin.paycore.wallet.domain.model.WalletId.newId(), customerId, available, reserved,
                Instant.parse("2026-08-17T12:00:00Z"));
        Wallet closed = wallet.close(BigDecimal.ZERO, false, Instant.parse("2026-08-17T12:00:01Z"));
        QueryOwnWalletService service = new QueryOwnWalletService(
                new InMemoryWallets(closed), new InMemoryBalances(Map.of(
                        available, new WalletBalances(BigDecimal.ZERO, BigDecimal.ZERO),
                        reserved, new WalletBalances(BigDecimal.ZERO, BigDecimal.ZERO))));

        assertThat(service.confirmCompleteUsdWallet(customerId)).isEmpty();
    }

    @Test
    void propagatesRetryableInfrastructureFailureDuringCompletenessConfirmation() {
        UUID customerId = UUID.randomUUID();
        Wallet wallet = Wallet.unfunded(
                dev.martin.paycore.wallet.domain.model.WalletId.newId(), customerId,
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-08-17T12:00:00Z"));
        RuntimeException failure = new RuntimeException(
                "serialization failure", new SQLException("serialization failure", "40001"));
        WalletBalanceReader balances = new WalletBalanceReader() {
            @Override
            public WalletBalances read(UUID availableAccountId, UUID reservedAccountId) {
                throw failure;
            }

            @Override
            public WalletBalances readForUpdate(UUID availableAccountId, UUID reservedAccountId) {
                throw failure;
            }

            @Override
            public WalletBalances readForUpdateForClose(UUID availableAccountId, UUID reservedAccountId) {
                throw failure;
            }
        };

        QueryOwnWalletService service = new QueryOwnWalletService(new InMemoryWallets(wallet), balances);

        assertThatThrownBy(() -> service.confirmCompleteUsdWallet(customerId))
                .isSameAs(failure);
    }

    private static final class InMemoryWallets implements WalletStore {
        private final Wallet wallet;

        private InMemoryWallets(Wallet wallet) {
            this.wallet = wallet;
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }

        @Override
        public Optional<Wallet> lockAndFindByCustomerId(UUID customerId) {
            return findByCustomerId(customerId);
        }

        @Override
        public Wallet claim(Wallet candidate) {
            return wallet;
        }

        @Override
        public Optional<Wallet> findByCustomerId(UUID customerId) {
            return Optional.of(wallet).filter(value -> value.customerId().equals(customerId));
        }

        @Override
        public Wallet save(Wallet wallet) {
            return wallet;
        }
    }

    private static final class InMemoryBalances implements WalletBalanceReader {
        private final Map<UUID, WalletBalances> balances;

        private InMemoryBalances(Map<UUID, WalletBalances> balances) {
            this.balances = balances;
        }

        @Override
        public WalletBalances read(UUID availableAccountId, UUID reservedAccountId) {
            WalletBalances available = balances.get(availableAccountId);
            WalletBalances reserved = balances.get(reservedAccountId);
            return new WalletBalances(available.available(), reserved.reserved());
        }

        @Override
        public WalletBalances readForUpdate(UUID availableAccountId, UUID reservedAccountId) {
            return read(availableAccountId, reservedAccountId);
        }

        @Override
        public WalletBalances readForUpdateForClose(UUID availableAccountId, UUID reservedAccountId) {
            return read(availableAccountId, reservedAccountId);
        }
    }
}
