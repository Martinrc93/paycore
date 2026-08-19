package dev.martin.paycore.wallet.application.query;

import java.util.Optional;
import java.util.UUID;

public interface WalletAccess {

    WalletView query(UUID customerId);

    Optional<WalletView> confirmCompleteUsdWallet(UUID customerId);
}
