package dev.martin.paycore.wallet.infrastructure.config;

import dev.martin.paycore.wallet.application.port.out.LedgerAccountProvisioner;
import dev.martin.paycore.wallet.application.port.out.WalletBalanceReader;
import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.application.port.out.WalletAccountLifecycle;
import dev.martin.paycore.wallet.application.lifecycle.WalletLifecycleService;
import dev.martin.paycore.wallet.application.provisioning.ProvisionWalletService;
import dev.martin.paycore.wallet.application.query.QueryOwnWalletService;
import dev.martin.paycore.wallet.application.funding.WalletFundingActivationService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WalletConfiguration {

    @Bean
    ProvisionWalletService provisionWalletService(WalletStore wallets,
            LedgerAccountProvisioner accounts, Clock clock) {
        return new ProvisionWalletService(
                wallets, accounts, clock, dev.martin.paycore.wallet.domain.model.WalletId::newId,
                java.util.UUID::randomUUID);
    }

    @Bean
    QueryOwnWalletService queryOwnWalletService(WalletStore wallets, WalletBalanceReader balances) {
        return new QueryOwnWalletService(wallets, balances);
    }

    @Bean
    WalletLifecycleService walletLifecycleService(WalletStore wallets, WalletBalanceReader balances,
            WalletAccountLifecycle accounts, Clock clock) {
        return new WalletLifecycleService(wallets, balances, accounts, clock);
    }

    @Bean
    WalletFundingActivationService walletFundingActivationService(WalletStore wallets) {
        return new WalletFundingActivationService(wallets);
    }
}
