package dev.martin.paycore.wallet.application.port.out;

public interface LedgerAccountProvisioner {

    void provision(WalletAccountProvisioning provisioning);
}
