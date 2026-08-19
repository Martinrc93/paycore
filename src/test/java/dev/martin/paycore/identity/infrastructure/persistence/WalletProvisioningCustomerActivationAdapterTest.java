package dev.martin.paycore.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.wallet.application.query.WalletAccess;
import dev.martin.paycore.wallet.application.query.WalletView;
import dev.martin.paycore.wallet.application.provisioning.ProvisionWallet;
import dev.martin.paycore.wallet.application.provisioning.ProvisionWalletCommand;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class WalletProvisioningCustomerActivationAdapterTest {

    private static final CustomerId CUSTOMER_ID = new CustomerId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final Instant ACTIVATED_AT = Instant.parse("2026-08-17T12:00:00Z");

    @Test
    void provisionsWalletBeforeCommittingPendingCustomerActivation() {
        CustomerAccessPersistenceAdapter customers = mock(CustomerAccessPersistenceAdapter.class);
        ProvisionWallet walletProvisioning = mock(ProvisionWallet.class);
        WalletAccess walletAccess = mock(WalletAccess.class);
        PostgresTransactionExecutor transactions = executor();
        CustomerAccess pending = new CustomerAccess(CUSTOMER_ID, CustomerStatus.PENDING_VERIFICATION);
        CustomerAccess active = new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE);
        when(customers.lockByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(pending));
        when(walletAccess.confirmCompleteUsdWallet(CUSTOMER_ID.value()))
                .thenReturn(Optional.of(mock(WalletView.class)));
        when(customers.activatePendingIfCurrent(CUSTOMER_ID, ACTIVATED_AT)).thenReturn(Optional.of(active));

        WalletProvisioningCustomerActivationAdapter adapter = new WalletProvisioningCustomerActivationAdapter(
                customers, walletProvisioning, walletAccess, transactions);

        assertThat(adapter.activatePending(CUSTOMER_ID, ACTIVATED_AT)).contains(active);

        var order = inOrder(walletProvisioning, walletAccess, customers);
        order.verify(walletProvisioning).provision(new ProvisionWalletCommand(CUSTOMER_ID.value(), WalletCurrency.USD));
        order.verify(walletAccess).confirmCompleteUsdWallet(CUSTOMER_ID.value());
        order.verify(customers).activatePendingIfCurrent(CUSTOMER_ID, ACTIVATED_AT);
    }

    @Test
    void doesNotActivatePendingCustomerWhenProvisionedWalletIsIncomplete() {
        CustomerAccessPersistenceAdapter customers = mock(CustomerAccessPersistenceAdapter.class);
        ProvisionWallet walletProvisioning = mock(ProvisionWallet.class);
        WalletAccess walletAccess = mock(WalletAccess.class);
        when(customers.lockByCustomerId(CUSTOMER_ID)).thenReturn(
                Optional.of(new CustomerAccess(CUSTOMER_ID, CustomerStatus.PENDING_VERIFICATION)));
        when(walletAccess.confirmCompleteUsdWallet(CUSTOMER_ID.value())).thenReturn(Optional.empty());

        WalletProvisioningCustomerActivationAdapter adapter = new WalletProvisioningCustomerActivationAdapter(
                customers, walletProvisioning, walletAccess, executor());

        assertThat(adapter.activatePending(CUSTOMER_ID, ACTIVATED_AT)).isEmpty();
        verify(customers, never()).activatePendingIfCurrent(any(), any());
    }

    @Test
    void rollsBackProvisionedWalletWhenCompletenessConfirmationIsEmpty() {
        CustomerAccessPersistenceAdapter customers = mock(CustomerAccessPersistenceAdapter.class);
        ProvisionWallet walletProvisioning = mock(ProvisionWallet.class);
        WalletAccess walletAccess = mock(WalletAccess.class);
        AtomicBoolean walletPersisted = new AtomicBoolean();
        when(customers.lockByCustomerId(CUSTOMER_ID)).thenReturn(
                Optional.of(new CustomerAccess(CUSTOMER_ID, CustomerStatus.PENDING_VERIFICATION)));
        doAnswer(invocation -> {
            walletPersisted.set(true);
            return null;
        }).when(walletProvisioning).provision(any());
        when(walletAccess.confirmCompleteUsdWallet(CUSTOMER_ID.value())).thenReturn(Optional.empty());

        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                try {
                    return action.doInTransaction(null);
                } catch (RuntimeException failure) {
                    walletPersisted.set(false);
                    throw failure;
                }
            }
        };
        WalletProvisioningCustomerActivationAdapter adapter = new WalletProvisioningCustomerActivationAdapter(
                customers, walletProvisioning, walletAccess,
                new PostgresTransactionExecutor(transactions, 1));

        assertThat(adapter.activatePending(CUSTOMER_ID, ACTIVATED_AT)).isEmpty();
        assertThat(walletPersisted).as("transaction rollback").isFalse();
        verify(customers, never()).activatePendingIfCurrent(any(), any());
    }

    @Test
    void doesNotActivatePendingCustomerWhenWalletCompletenessCheckFails() {
        CustomerAccessPersistenceAdapter customers = mock(CustomerAccessPersistenceAdapter.class);
        ProvisionWallet walletProvisioning = mock(ProvisionWallet.class);
        WalletAccess walletAccess = mock(WalletAccess.class);
        when(customers.lockByCustomerId(CUSTOMER_ID)).thenReturn(
                Optional.of(new CustomerAccess(CUSTOMER_ID, CustomerStatus.PENDING_VERIFICATION)));
        when(walletAccess.confirmCompleteUsdWallet(CUSTOMER_ID.value()))
                .thenThrow(new IllegalStateException("wallet consistency unavailable"));

        WalletProvisioningCustomerActivationAdapter adapter = new WalletProvisioningCustomerActivationAdapter(
                customers, walletProvisioning, walletAccess, executor());

        assertThat(adapter.activatePending(CUSTOMER_ID, ACTIVATED_AT)).isEmpty();
        verify(customers, never()).activatePendingIfCurrent(any(), any());
    }

    @Test
    void reportsFailedActivationWhenWalletProvisioningFails() {
        CustomerAccessPersistenceAdapter customers = mock(CustomerAccessPersistenceAdapter.class);
        ProvisionWallet walletProvisioning = mock(ProvisionWallet.class);
        WalletAccess walletAccess = mock(WalletAccess.class);
        PostgresTransactionExecutor transactions = executor();
        when(customers.lockByCustomerId(CUSTOMER_ID)).thenReturn(
                Optional.of(new CustomerAccess(CUSTOMER_ID, CustomerStatus.PENDING_VERIFICATION)));
        when(walletProvisioning.provision(any())).thenThrow(new IllegalStateException("wallet unavailable"));

        WalletProvisioningCustomerActivationAdapter adapter = new WalletProvisioningCustomerActivationAdapter(
                customers, walletProvisioning, walletAccess, transactions);

        assertThat(adapter.activatePending(CUSTOMER_ID, ACTIVATED_AT)).isEmpty();
        verify(customers, never()).activatePendingIfCurrent(any(), any());
    }

    @Test
    void retriesSerializationFailureWithoutReusingTheFailedTransaction() {
        CustomerAccessPersistenceAdapter customers = mock(CustomerAccessPersistenceAdapter.class);
        ProvisionWallet walletProvisioning = mock(ProvisionWallet.class);
        WalletAccess walletAccess = mock(WalletAccess.class);
        AtomicInteger transactionAttempts = new AtomicInteger();
        PostgresTransactionExecutor transactions = executor(transactionAttempts, 3);
        CustomerAccess active = new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE);
        when(customers.lockByCustomerId(CUSTOMER_ID))
                .thenThrow(serializationFailure())
                .thenReturn(Optional.of(active));
        when(walletAccess.confirmCompleteUsdWallet(CUSTOMER_ID.value()))
                .thenReturn(Optional.of(mock(WalletView.class)));

        WalletProvisioningCustomerActivationAdapter adapter = new WalletProvisioningCustomerActivationAdapter(
                customers, walletProvisioning, walletAccess, transactions);

        assertThat(adapter.activatePending(CUSTOMER_ID, ACTIVATED_AT)).contains(active);
        assertThat(transactionAttempts).hasValue(2);
        verify(walletProvisioning, never()).provision(any());
    }

    @Test
    void retriesSerializationFailureDuringWalletCompletenessConfirmation() {
        CustomerAccessPersistenceAdapter customers = mock(CustomerAccessPersistenceAdapter.class);
        ProvisionWallet walletProvisioning = mock(ProvisionWallet.class);
        WalletAccess walletAccess = mock(WalletAccess.class);
        AtomicInteger transactionAttempts = new AtomicInteger();
        PostgresTransactionExecutor transactions = executor(transactionAttempts, 3);
        CustomerAccess pending = new CustomerAccess(CUSTOMER_ID, CustomerStatus.PENDING_VERIFICATION);
        CustomerAccess active = new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE);
        when(customers.lockByCustomerId(CUSTOMER_ID)).thenAnswer(invocation -> Optional.of(pending));
        when(walletAccess.confirmCompleteUsdWallet(CUSTOMER_ID.value()))
                .thenThrow(serializationFailure())
                .thenReturn(Optional.of(mock(WalletView.class)));
        when(customers.activatePendingIfCurrent(CUSTOMER_ID, ACTIVATED_AT)).thenReturn(Optional.of(active));

        WalletProvisioningCustomerActivationAdapter adapter = new WalletProvisioningCustomerActivationAdapter(
                customers, walletProvisioning, walletAccess, transactions);

        assertThat(adapter.activatePending(CUSTOMER_ID, ACTIVATED_AT)).contains(active);
        assertThat(transactionAttempts).hasValue(2);
        verify(customers).activatePendingIfCurrent(CUSTOMER_ID, ACTIVATED_AT);
    }

    @Test
    void confirmsActiveCustomerOnlyWhenWalletApplicationReportsACompleteUsdWallet() {
        CustomerAccessPersistenceAdapter customers = mock(CustomerAccessPersistenceAdapter.class);
        ProvisionWallet walletProvisioning = mock(ProvisionWallet.class);
        WalletAccess walletAccess = mock(WalletAccess.class);
        CustomerAccess active = new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE);
        when(customers.lockByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(active));
        when(walletAccess.confirmCompleteUsdWallet(CUSTOMER_ID.value()))
                .thenReturn(Optional.of(mock(WalletView.class)));

        WalletProvisioningCustomerActivationAdapter adapter = new WalletProvisioningCustomerActivationAdapter(
                customers, walletProvisioning, walletAccess, executor());

        assertThat(adapter.confirmActive(CUSTOMER_ID)).contains(active);
        verify(walletProvisioning, never()).provision(any());
    }

    @Test
    void deniesActiveCustomerWhenWalletApplicationCannotConfirmACompleteWallet() {
        CustomerAccessPersistenceAdapter customers = mock(CustomerAccessPersistenceAdapter.class);
        ProvisionWallet walletProvisioning = mock(ProvisionWallet.class);
        WalletAccess walletAccess = mock(WalletAccess.class);
        when(customers.lockByCustomerId(CUSTOMER_ID)).thenReturn(
                Optional.of(new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE)));
        when(walletAccess.confirmCompleteUsdWallet(CUSTOMER_ID.value())).thenReturn(Optional.empty());

        WalletProvisioningCustomerActivationAdapter adapter = new WalletProvisioningCustomerActivationAdapter(
                customers, walletProvisioning, walletAccess, executor());

        assertThat(adapter.confirmActive(CUSTOMER_ID)).isEmpty();
        verify(walletProvisioning, never()).provision(any());
    }

    private static PostgresTransactionExecutor executor() {
        return executor(new AtomicInteger(), 3);
    }

    private static PostgresTransactionExecutor executor(AtomicInteger attempts, int maxAttempts) {
        TransactionOperations operations = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                attempts.incrementAndGet();
                return action.doInTransaction(null);
            }
        };
        return new PostgresTransactionExecutor(operations, maxAttempts);
    }

    private static DataAccessResourceFailureException serializationFailure() {
        return new DataAccessResourceFailureException(
                "serialization failure", new SQLException("serialization failure", "40001"));
    }
}
