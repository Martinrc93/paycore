package dev.martin.paycore.wallet.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WalletTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-17T12:00:00Z");
    private final UUID customerId = UUID.randomUUID();
    private final UUID availableAccountId = UUID.randomUUID();
    private final UUID reservedAccountId = UUID.randomUUID();

    @Test
    void startsUnfundedAndCanBeActivatedOnce() {
        Wallet wallet = wallet();

        Wallet active = wallet.activate(CREATED_AT.plusSeconds(1));

        assertThat(wallet.status()).isEqualTo(WalletStatus.UNFUNDED);
        assertThat(active.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(active.activatedAt()).isEqualTo(CREATED_AT.plusSeconds(1));
        assertThat(active.preBlockStatus()).isNull();
    }

    @Test
    void blockingPreservesTheOperationalStateAndUnblockingRestoresIt() {
        Wallet active = wallet().activate(CREATED_AT.plusSeconds(1));

        Wallet blocked = active.block(CREATED_AT.plusSeconds(2));
        Wallet restored = blocked.unblock(CREATED_AT.plusSeconds(3));

        assertThat(blocked.status()).isEqualTo(WalletStatus.BLOCKED);
        assertThat(blocked.preBlockStatus()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(restored.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(restored.preBlockStatus()).isNull();
    }

    @Test
    void closeRequiresZeroBalanceAndNoActiveReservations() {
        Wallet wallet = wallet();

        assertThatThrownBy(() -> wallet.close(new BigDecimal("0.01"), false, CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> wallet.close(BigDecimal.ZERO, true, CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(wallet.close(BigDecimal.ZERO, false, CREATED_AT.plusSeconds(1)).status())
                .isEqualTo(WalletStatus.CLOSED);
    }

    @Test
    void rejectsNonUsdWalletsAndInvalidLifecycleTransitions() {
        assertThatThrownBy(() -> new Wallet(
                WalletId.newId(), customerId, WalletCurrency.EUR, availableAccountId, reservedAccountId,
                WalletStatus.UNFUNDED, null, null, CREATED_AT, CREATED_AT, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> wallet().unblock(CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> wallet().activate(CREATED_AT.plusSeconds(1))
                .activate(CREATED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activeWalletsRequireAnActivationInstant() {
        assertThatThrownBy(() -> new Wallet(
                WalletId.newId(), customerId, WalletCurrency.USD, availableAccountId, reservedAccountId,
                WalletStatus.ACTIVE, null, null, CREATED_AT, CREATED_AT, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blockingAndUnblockingAnUnfundedWalletRestoresUnfundedState() {
        Wallet blocked = wallet().block(CREATED_AT.plusSeconds(1));

        Wallet restored = blocked.unblock(CREATED_AT.plusSeconds(2));

        assertThat(blocked.preBlockStatus()).isEqualTo(WalletStatus.UNFUNDED);
        assertThat(restored.status()).isEqualTo(WalletStatus.UNFUNDED);
        assertThat(restored.preBlockStatus()).isNull();
    }

    @Test
    void reblockingDoesNotOverwriteTheOriginalOperationalState() {
        Wallet blocked = wallet().block(CREATED_AT.plusSeconds(1));

        Wallet reblocked = blocked.block(CREATED_AT.plusSeconds(2));

        assertThat(reblocked).isSameAs(blocked);
        assertThat(reblocked.preBlockStatus()).isEqualTo(WalletStatus.UNFUNDED);
    }

    private Wallet wallet() {
        return Wallet.unfunded(
                WalletId.newId(), customerId, availableAccountId, reservedAccountId, CREATED_AT);
    }
}
