package dev.martin.paycore.wallet.application.port.out;

import java.util.UUID;

public interface WalletAccountLifecycle {

    void block(UUID availableAccountId, UUID reservedAccountId);

    void unblock(UUID availableAccountId, UUID reservedAccountId);

    void close(UUID availableAccountId, UUID reservedAccountId);
}
