package dev.martin.paycore.wallet.application.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.wallet.application.port.out.LedgerAccountProvisioner;
import dev.martin.paycore.wallet.application.port.out.WalletAccountProvisioning;
import dev.martin.paycore.wallet.application.port.out.WalletBalanceReader;
import dev.martin.paycore.wallet.application.port.out.WalletBalances;
import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.domain.model.Wallet;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import dev.martin.paycore.wallet.domain.model.WalletId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ProvisionWalletServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private final UUID customerId = UUID.randomUUID();
    private final InMemoryWallets wallets = new InMemoryWallets();
    private final InMemoryProvisioner accounts = new InMemoryProvisioner();
    private final InMemoryBalances balances = new InMemoryBalances();
    private final ProvisionWalletService service = new ProvisionWalletService(
            wallets,
            accounts,
            Clock.fixed(NOW, ZoneOffset.UTC),
            WalletId::newId,
            UUID::randomUUID);

    @Test
    void provisionsExactlyTwoUsdNonNegativeLiabilityAccountsAndProjections() {
        Wallet wallet = service.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        assertThat(wallet.status()).isEqualTo(dev.martin.paycore.wallet.domain.model.WalletStatus.UNFUNDED);
        assertThat(accounts.provisioned).hasSize(1);
        assertThat(accounts.provisioned.get(0).availableAccountId())
                .isEqualTo(wallet.availableAccountId());
        assertThat(accounts.provisioned.get(0).reservedAccountId())
                .isEqualTo(wallet.reservedAccountId());
    }

    @Test
    void repeatedProvisioningReturnsTheExistingWalletWithoutCreatingMoreAccounts() {
        Wallet first = service.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));
        Wallet second = service.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        assertThat(second).isEqualTo(first);
        assertThat(accounts.provisioned).hasSize(1);
    }

    @Test
    void rejectsUnsupportedCurrencyBeforeClaimingOrCreatingAnything() {
        assertThatThrownBy(() -> service.provision(new ProvisionWalletCommand(customerId, WalletCurrency.EUR)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(wallets.wallets).isEmpty();
        assertThat(accounts.provisioned).isEmpty();
    }

    private static final class InMemoryWallets implements WalletStore {
        private final Map<UUID, Wallet> wallets = new HashMap<>();

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }

        @Override
        public Optional<Wallet> lockAndFindByCustomerId(UUID customerId) {
            return Optional.ofNullable(wallets.get(customerId));
        }

        @Override
        public Wallet claim(Wallet candidate) {
            return wallets.computeIfAbsent(candidate.customerId(), ignored -> candidate);
        }

        @Override
        public Optional<Wallet> findByCustomerId(UUID customerId) {
            return Optional.ofNullable(wallets.get(customerId));
        }

        @Override
        public Wallet save(Wallet wallet) {
            wallets.put(wallet.customerId(), wallet);
            return wallet;
        }
    }

    private static final class InMemoryProvisioner implements LedgerAccountProvisioner {
        private final List<WalletAccountProvisioning> provisioned = new ArrayList<>();

        @Override
        public void provision(WalletAccountProvisioning provisioning) {
            provisioned.add(provisioning);
        }
    }

    private static final class InMemoryBalances implements WalletBalanceReader {

        @Override
        public WalletBalances read(UUID availableAccountId, UUID reservedAccountId) {
            return new WalletBalances(BigDecimal.ZERO, BigDecimal.ZERO);
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
