package dev.martin.paycore.wallet.application.query;

import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.application.port.out.WalletBalanceReader;
import dev.martin.paycore.wallet.application.port.out.WalletBalances;
import dev.martin.paycore.wallet.domain.model.Wallet;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import dev.martin.paycore.wallet.domain.model.WalletStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class QueryOwnWalletService implements WalletAccess {

    private final WalletStore wallets;
    private final WalletBalanceReader balances;

    public QueryOwnWalletService(WalletStore wallets, WalletBalanceReader balances) {
        this.wallets = Objects.requireNonNull(wallets, "wallets");
        this.balances = Objects.requireNonNull(balances, "balances");
    }

    public WalletView query(UUID customerId) {
        Objects.requireNonNull(customerId, "customerId");
        return wallets.inTransaction(() -> wallets.findByCustomerId(customerId)
                .map(this::view)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found")));
    }

    @Override
    public Optional<WalletView> confirmCompleteUsdWallet(UUID customerId) {
        Objects.requireNonNull(customerId, "customerId");
        return wallets.inTransaction(() -> wallets.findByCustomerId(customerId)
                .map(this::view)
                .filter(view -> view.currency() == WalletCurrency.USD)
                .filter(view -> view.status() == WalletStatus.UNFUNDED || view.status() == WalletStatus.ACTIVE));
    }

    private WalletView view(Wallet wallet) {
        WalletBalances walletBalances = balances.read(wallet.availableAccountId(), wallet.reservedAccountId());
        return new WalletView(wallet.id(), wallet.status(), wallet.currency(),
                walletBalances.available(), walletBalances.reserved(), walletBalances.total());
    }

}
