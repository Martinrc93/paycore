package dev.martin.paycore.wallet.application.lifecycle;

import dev.martin.paycore.wallet.application.port.out.WalletAccountLifecycle;
import dev.martin.paycore.wallet.application.port.out.WalletBalanceReader;
import dev.martin.paycore.wallet.application.port.out.WalletBalances;
import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.domain.model.Wallet;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class WalletLifecycleService {

    private final WalletStore wallets;
    private final WalletBalanceReader balances;
    private final WalletAccountLifecycle accounts;
    private final Clock clock;

    public WalletLifecycleService(WalletStore wallets, WalletBalanceReader balances,
            WalletAccountLifecycle accounts, Clock clock) {
        this.wallets = Objects.requireNonNull(wallets, "wallets");
        this.balances = Objects.requireNonNull(balances, "balances");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Wallet block(UUID customerId) {
        return wallets.inTransaction(() -> {
            Wallet wallet = lockedWallet(customerId);
            Wallet blocked = wallet.block(clock.instant());
            if (blocked == wallet) {
                return wallet;
            }
            accounts.block(wallet.availableAccountId(), wallet.reservedAccountId());
            return wallets.save(blocked);
        });
    }

    public Wallet unblock(UUID customerId) {
        return wallets.inTransaction(() -> {
            Wallet wallet = lockedWallet(customerId);
            Wallet unblocked = wallet.unblock(clock.instant());
            accounts.unblock(wallet.availableAccountId(), wallet.reservedAccountId());
            return wallets.save(unblocked);
        });
    }

    public Wallet close(UUID customerId) {
        return wallets.inTransaction(() -> {
            Wallet wallet = lockedWallet(customerId);
            WalletBalances current = balances.readForUpdateForClose(
                    wallet.availableAccountId(), wallet.reservedAccountId());
            BigDecimal total = current.total();
            Wallet closed = wallet.close(total, current.reserved().signum() != 0, clock.instant());
            accounts.close(wallet.availableAccountId(), wallet.reservedAccountId());
            return wallets.save(closed);
        });
    }

    private Wallet lockedWallet(UUID customerId) {
        Objects.requireNonNull(customerId, "customerId");
        return wallets.lockAndFindByCustomerId(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));
    }
}
