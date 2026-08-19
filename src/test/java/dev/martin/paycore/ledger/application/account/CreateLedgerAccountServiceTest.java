package dev.martin.paycore.ledger.application.account;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.ledger.application.port.out.LedgerAccountStore;
import dev.martin.paycore.ledger.application.port.out.LedgerBalanceStore;
import dev.martin.paycore.ledger.application.balance.LedgerAccountBalancePair;
import dev.martin.paycore.ledger.application.balance.LedgerBalanceQuery;
import dev.martin.paycore.ledger.application.balance.LedgerReconciliationResult;
import dev.martin.paycore.ledger.domain.model.FinancialTransaction;
import dev.martin.paycore.ledger.domain.model.LedgerAccountBalance;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerAccount;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import java.util.Optional;
import java.util.Collection;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreateLedgerAccountServiceTest {

    @Test
    void createsTheAccountWithThePreassignedIdAndMetadata() {
        InMemoryAccounts accounts = new InMemoryAccounts();
        RecordingBalances balances = new RecordingBalances();
        CreateLedgerAccountService service = new CreateLedgerAccountService(accounts, balances);
        LedgerAccountId id = LedgerAccountId.newId();

        LedgerAccount account = service.create(new CreateLedgerAccountCommand(
                id, LedgerAccountType.LIABILITY, "wallet-available", CurrencyCode.USD,
                LedgerBalancePolicy.NON_NEGATIVE));

        assertThat(account.id()).isEqualTo(id);
        assertThat(account.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(account.balancePolicy()).isEqualTo(LedgerBalancePolicy.NON_NEGATIVE);
        assertThat(accounts.saved).isSameAs(account);
        assertThat(balances.initialized).isEqualTo(id.value());
    }

    private static final class InMemoryAccounts implements LedgerAccountStore {
        private LedgerAccount saved;

        @Override
        public Optional<LedgerAccount> findById(LedgerAccountId id) {
            return Optional.ofNullable(saved).filter(account -> account.id().equals(id));
        }

        @Override
        public LedgerAccount save(LedgerAccount account) {
            saved = account;
            return account;
        }
    }

    private static final class RecordingBalances implements LedgerBalanceStore {
        private UUID initialized;

        @Override public void initialize(UUID accountId) { initialized = accountId; }
        @Override public LedgerAccountBalance find(LedgerBalanceQuery query) { throw new UnsupportedOperationException(); }
        @Override public LedgerAccountBalancePair findPair(Collection<UUID> accountIds) { throw new UnsupportedOperationException(); }
        @Override public LedgerAccountBalancePair findPairForUpdate(Collection<UUID> accountIds) { throw new UnsupportedOperationException(); }
        @Override public LedgerReconciliationResult reconcile(UUID accountId) { throw new UnsupportedOperationException(); }
        @Override public LedgerReconciliationResult rebuild(UUID accountId) { throw new UnsupportedOperationException(); }
        @Override public void lockAndValidate(FinancialTransaction transaction) { throw new UnsupportedOperationException(); }
        @Override public void apply(FinancialTransaction transaction) { throw new UnsupportedOperationException(); }
    }
}
