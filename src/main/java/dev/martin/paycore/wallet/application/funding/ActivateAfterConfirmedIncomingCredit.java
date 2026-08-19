package dev.martin.paycore.wallet.application.funding;

import dev.martin.paycore.wallet.domain.model.Wallet;
import java.time.Instant;
import java.util.UUID;

public interface ActivateAfterConfirmedIncomingCredit {

    Wallet activateAfterConfirmedIncomingCredit(UUID customerId, Instant activatedAt);
}
