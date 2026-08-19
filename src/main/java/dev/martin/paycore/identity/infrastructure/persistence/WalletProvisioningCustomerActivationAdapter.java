package dev.martin.paycore.identity.infrastructure.persistence;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.application.port.out.CustomerActivationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.wallet.application.query.WalletAccess;
import dev.martin.paycore.wallet.application.provisioning.ProvisionWallet;
import dev.martin.paycore.wallet.application.provisioning.ProvisionWalletCommand;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class WalletProvisioningCustomerActivationAdapter implements CustomerActivationPort {

    private final CustomerAccessPersistenceAdapter customers;
    private final ProvisionWallet walletProvisioning;
    private final WalletAccess walletAccess;
    private final PostgresTransactionExecutor transactions;

    public WalletProvisioningCustomerActivationAdapter(CustomerAccessPersistenceAdapter customers,
            ProvisionWallet walletProvisioning, WalletAccess walletAccess, PostgresTransactionExecutor transactions) {
        this.customers = Objects.requireNonNull(customers, "customers");
        this.walletProvisioning = Objects.requireNonNull(walletProvisioning, "walletProvisioning");
        this.walletAccess = Objects.requireNonNull(walletAccess, "walletAccess");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public Optional<CustomerAccess> activatePending(CustomerId customerId, Instant activatedAt) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(activatedAt, "activatedAt");
        try {
            return transactions.execute(() -> activateInTransaction(customerId, activatedAt));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CustomerAccess> confirmActive(CustomerId customerId) {
        Objects.requireNonNull(customerId, "customerId");
        try {
            return transactions.execute(() -> confirmActiveInTransaction(customerId));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private Optional<CustomerAccess> activateInTransaction(CustomerId customerId, Instant activatedAt) {
        Optional<CustomerAccess> locked = customers.lockByCustomerId(customerId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        if (locked.get().status() != CustomerStatus.PENDING_VERIFICATION) {
            return locked.filter(CustomerAccess::isActive);
        }

        walletProvisioning.provision(new ProvisionWalletCommand(customerId.value(), WalletCurrency.USD));
        if (walletAccess.confirmCompleteUsdWallet(customerId.value()).isEmpty()) {
            throw new IllegalStateException("Provisioned wallet is incomplete");
        }
        return customers.activatePendingIfCurrent(customerId, activatedAt);
    }

    private Optional<CustomerAccess> confirmActiveInTransaction(CustomerId customerId) {
        Optional<CustomerAccess> locked = customers.lockByCustomerId(customerId);
        if (locked.isEmpty() || !locked.get().isActive()) {
            return Optional.empty();
        }
        return walletAccess.confirmCompleteUsdWallet(customerId.value()).map(ignored -> locked.get());
    }
}
