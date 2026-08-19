package dev.martin.paycore.wallet.application.port.out;

import java.util.UUID;

public interface WalletBalanceReader {

    WalletBalances read(UUID availableAccountId, UUID reservedAccountId);

    WalletBalances readForUpdate(UUID availableAccountId, UUID reservedAccountId);

    WalletBalances readForUpdateForClose(UUID availableAccountId, UUID reservedAccountId);
}
