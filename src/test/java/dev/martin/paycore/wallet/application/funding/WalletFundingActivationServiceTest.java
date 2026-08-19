package dev.martin.paycore.wallet.application.funding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.domain.model.Wallet;
import dev.martin.paycore.wallet.domain.model.WalletId;
import dev.martin.paycore.wallet.domain.model.WalletStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class WalletFundingActivationServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-17T12:00:00Z");
    private static final Instant ACTIVATED_AT = CREATED_AT.plusSeconds(1);

    @Test
    void activatesAnUnfundedWalletAfterAConfirmedIncomingCredit() {
        UUID customerId = UUID.randomUUID();
        Wallet wallet = wallet(customerId);
        InMemoryWallets wallets = new InMemoryWallets(wallet);

        Wallet activated = new WalletFundingActivationService(wallets)
                .activateAfterConfirmedIncomingCredit(customerId, ACTIVATED_AT);

        assertThat(activated.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(activated.activatedAt()).isEqualTo(ACTIVATED_AT);
        assertThat(wallets.saved).isSameAs(activated);
        assertThat(wallets.transactionCount).isEqualTo(1);
    }

    @Test
    void repeatsOfAConfirmedCreditReturnAnAlreadyActiveWalletWithoutChangingIt() {
        UUID customerId = UUID.randomUUID();
        Wallet active = wallet(customerId).activate(ACTIVATED_AT);
        InMemoryWallets wallets = new InMemoryWallets(active);

        Wallet result = new WalletFundingActivationService(wallets)
                .activateAfterConfirmedIncomingCredit(customerId, ACTIVATED_AT.plusSeconds(1));

        assertThat(result).isSameAs(active);
        assertThat(wallets.saved).isNull();
    }

    @Test
    void doesNotActivateABlockedWallet() {
        UUID customerId = UUID.randomUUID();
        Wallet blocked = wallet(customerId).block(ACTIVATED_AT);

        assertThatThrownBy(() -> new WalletFundingActivationService(new InMemoryWallets(blocked))
                .activateAfterConfirmedIncomingCredit(customerId, ACTIVATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only an unfunded or active wallet can be activated after an incoming credit");
    }

    private static Wallet wallet(UUID customerId) {
        return Wallet.unfunded(WalletId.newId(), customerId, UUID.randomUUID(), UUID.randomUUID(), CREATED_AT);
    }

    private static final class InMemoryWallets implements WalletStore {
        private Wallet wallet;
        private Wallet saved;
        private int transactionCount;

        private InMemoryWallets(Wallet wallet) {
            this.wallet = wallet;
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            transactionCount++;
            return work.get();
        }

        @Override
        public Optional<Wallet> lockAndFindByCustomerId(UUID customerId) {
            return findByCustomerId(customerId);
        }

        @Override
        public Wallet claim(Wallet candidate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Wallet> findByCustomerId(UUID customerId) {
            return Optional.of(wallet).filter(value -> value.customerId().equals(customerId));
        }

        @Override
        public Wallet save(Wallet wallet) {
            saved = wallet;
            this.wallet = wallet;
            return wallet;
        }
    }
}
