package dev.martin.paycore.wallet.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.wallet.application.port.out.WalletAccountLifecycle;
import dev.martin.paycore.wallet.application.port.out.WalletBalanceReader;
import dev.martin.paycore.wallet.application.port.out.WalletBalances;
import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.domain.model.Wallet;
import dev.martin.paycore.wallet.domain.model.WalletId;
import dev.martin.paycore.wallet.domain.model.WalletStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class WalletLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    @Test
    void blocksBothAccountsAndPersistsThePreviousActiveStatus() {
        Fixture fixture = fixture(WalletStatus.ACTIVE, new BigDecimal("1.00"), BigDecimal.ZERO);

        Wallet blocked = fixture.service().block(fixture.customerId());

        assertThat(blocked.status()).isEqualTo(WalletStatus.BLOCKED);
        assertThat(blocked.preBlockStatus()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(fixture.accounts().operations).containsExactly("block");
        assertThat(fixture.wallets().saved.status()).isEqualTo(WalletStatus.BLOCKED);
    }

    @Test
    void unblocksBothAccountsAndRestoresThePersistedOperationalStatus() {
        Fixture fixture = fixture(WalletStatus.BLOCKED, BigDecimal.ZERO, BigDecimal.ZERO);

        Wallet restored = fixture.service().unblock(fixture.customerId());

        assertThat(restored.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(restored.preBlockStatus()).isNull();
        assertThat(fixture.accounts().operations).containsExactly("unblock");
    }

    @Test
    void closesBothAccountsOnlyWhenBothBalancesAreZero() {
        Fixture fixture = fixture(WalletStatus.UNFUNDED, BigDecimal.ZERO, BigDecimal.ZERO);

        Wallet closed = fixture.service().close(fixture.customerId());

        assertThat(closed.status()).isEqualTo(WalletStatus.CLOSED);
        assertThat(fixture.accounts().operations).containsExactly("close");
        assertThat(fixture.balances().closeReads).isEqualTo(1);
    }

    @Test
    void closesAnEmptyBlockedWalletUsingTheCloseBalanceRead() {
        Fixture fixture = fixture(WalletStatus.BLOCKED, BigDecimal.ZERO, BigDecimal.ZERO);

        Wallet closed = fixture.service().close(fixture.customerId());

        assertThat(closed.status()).isEqualTo(WalletStatus.CLOSED);
        assertThat(fixture.balances().closeReads).isEqualTo(1);
        assertThat(fixture.balances().forUpdateReads).isZero();
    }

    @Test
    void rejectsCloseWithValueOrReservedObligationBeforeChangingAnything() {
        Fixture funded = fixture(WalletStatus.ACTIVE, new BigDecimal("1.00"), BigDecimal.ZERO);
        assertThatThrownBy(() -> funded.service().close(funded.customerId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(funded.accounts().operations).isEmpty();
        assertThat(funded.wallets().saved).isNull();

        Fixture reserved = fixture(WalletStatus.ACTIVE, BigDecimal.ZERO, new BigDecimal("1.00"));
        assertThatThrownBy(() -> reserved.service().close(reserved.customerId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reserved.accounts().operations).isEmpty();
        assertThat(reserved.wallets().saved).isNull();
    }

    private static Fixture fixture(WalletStatus status, BigDecimal available, BigDecimal reserved) {
        UUID customerId = UUID.randomUUID();
        UUID availableId = UUID.randomUUID();
        UUID reservedId = UUID.randomUUID();
        Wallet wallet = Wallet.unfunded(WalletId.newId(), customerId, availableId, reservedId, NOW);
        if (status == WalletStatus.ACTIVE) {
            wallet = wallet.activate(NOW);
        } else if (status == WalletStatus.BLOCKED) {
            wallet = wallet.activate(NOW).block(NOW);
        } else if (status == WalletStatus.CLOSED) {
            wallet = wallet.close(BigDecimal.ZERO, false, NOW);
        }
        InMemoryWallets wallets = new InMemoryWallets(wallet);
        InMemoryBalances balances = new InMemoryBalances(new WalletBalances(available, reserved));
        InMemoryAccounts accounts = new InMemoryAccounts();
        WalletLifecycleService service = new WalletLifecycleService(
                wallets, balances, accounts, Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(customerId, wallets, balances, accounts, service);
    }

    private record Fixture(UUID customerId, InMemoryWallets wallets,
            InMemoryBalances balances, InMemoryAccounts accounts, WalletLifecycleService service) {
    }

    private static final class InMemoryWallets implements WalletStore {
        private Wallet wallet;
        private Wallet saved;

        private InMemoryWallets(Wallet wallet) {
            this.wallet = wallet;
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }

        @Override
        public Optional<Wallet> lockAndFindByCustomerId(UUID customerId) {
            return Optional.of(wallet).filter(value -> value.customerId().equals(customerId));
        }

        @Override
        public Wallet claim(Wallet candidate) {
            wallet = candidate;
            return candidate;
        }

        @Override
        public Optional<Wallet> findByCustomerId(UUID customerId) {
            return lockAndFindByCustomerId(customerId);
        }

        @Override
        public Wallet save(Wallet wallet) {
            saved = wallet;
            this.wallet = wallet;
            return wallet;
        }
    }

    private static final class InMemoryBalances implements WalletBalanceReader {
        private final WalletBalances values;
        private int forUpdateReads;
        private int closeReads;

        private InMemoryBalances(WalletBalances values) {
            this.values = values;
        }

        @Override
        public WalletBalances read(UUID availableAccountId, UUID reservedAccountId) {
            return values;
        }

        @Override
        public WalletBalances readForUpdate(UUID availableAccountId, UUID reservedAccountId) {
            forUpdateReads++;
            return values;
        }

        @Override
        public WalletBalances readForUpdateForClose(UUID availableAccountId, UUID reservedAccountId) {
            closeReads++;
            return values;
        }
    }

    private static final class InMemoryAccounts implements WalletAccountLifecycle {
        private final java.util.List<String> operations = new java.util.ArrayList<>();

        @Override public void block(UUID availableAccountId, UUID reservedAccountId) { operations.add("block"); }
        @Override public void unblock(UUID availableAccountId, UUID reservedAccountId) { operations.add("unblock"); }
        @Override public void close(UUID availableAccountId, UUID reservedAccountId) { operations.add("close"); }
    }
}
