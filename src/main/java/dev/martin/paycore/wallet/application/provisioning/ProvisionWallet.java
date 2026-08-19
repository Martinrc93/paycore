package dev.martin.paycore.wallet.application.provisioning;

import dev.martin.paycore.wallet.domain.model.Wallet;

public interface ProvisionWallet {

    Wallet provision(ProvisionWalletCommand command);
}
