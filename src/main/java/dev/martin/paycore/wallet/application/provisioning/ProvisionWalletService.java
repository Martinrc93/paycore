package dev.martin.paycore.wallet.application.provisioning;

import dev.martin.paycore.wallet.application.port.out.LedgerAccountProvisioner;
import dev.martin.paycore.wallet.application.port.out.WalletAccountProvisioning;
import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.domain.model.Wallet;
import dev.martin.paycore.wallet.domain.model.WalletId;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class ProvisionWalletService implements ProvisionWallet {

    private final WalletStore wallets;
    private final LedgerAccountProvisioner accounts;
    private final Clock clock;
    private final Supplier<WalletId> walletIds;
    private final Supplier<UUID> accountIds;

    public ProvisionWalletService(WalletStore wallets, LedgerAccountProvisioner accounts,
            Clock clock, Supplier<WalletId> walletIds, Supplier<UUID> accountIds) {
        this.wallets = Objects.requireNonNull(wallets, "wallets");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.walletIds = Objects.requireNonNull(walletIds, "walletIds");
        this.accountIds = Objects.requireNonNull(accountIds, "accountIds");
    }

    @Override
    public Wallet provision(ProvisionWalletCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.currency() != WalletCurrency.USD) {
            throw new IllegalArgumentException("Wallet currency must be USD");
        }

        return wallets.inTransaction(() -> provisionInTransaction(command.customerId()));
    }

    private Wallet provisionInTransaction(UUID customerId) {
        return wallets.lockAndFindByCustomerId(customerId).orElseGet(() -> {
            Wallet candidate = Wallet.unfunded(
                    walletIds.get(), customerId, accountIds.get(), accountIds.get(), clock.instant());
            accounts.provision(new WalletAccountProvisioning(
                    customerId, candidate.availableAccountId(), candidate.reservedAccountId()));
            Wallet claimed = wallets.claim(candidate);
            if (!claimed.id().equals(candidate.id())) {
                throw new IllegalStateException("Wallet ownership changed during provisioning");
            }
            return claimed;
        });
    }

}
