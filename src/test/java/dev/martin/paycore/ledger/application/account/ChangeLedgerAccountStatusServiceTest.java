package dev.martin.paycore.ledger.application.account;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.ledger.application.port.out.LedgerAccountStore;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerAccount;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChangeLedgerAccountStatusServiceTest {

    @Test
    void changesAccountStatusThroughTheLedgerApplicationContract() {
        LedgerAccount account = LedgerAccount.open(LedgerAccountId.newId(), LedgerAccountType.LIABILITY,
                "wallet", CurrencyCode.USD, LedgerBalancePolicy.NON_NEGATIVE);
        InMemoryAccounts accounts = new InMemoryAccounts(account);
        ChangeLedgerAccountStatusService service = new ChangeLedgerAccountStatusService(accounts);

        assertThat(service.block(account.id()).status()).isEqualTo(dev.martin.paycore.ledger.domain.model.LedgerAccountStatus.BLOCKED);
        assertThat(service.unblock(account.id()).status()).isEqualTo(dev.martin.paycore.ledger.domain.model.LedgerAccountStatus.OPEN);
        assertThat(service.close(account.id()).status()).isEqualTo(dev.martin.paycore.ledger.domain.model.LedgerAccountStatus.CLOSED);
    }

    private static final class InMemoryAccounts implements LedgerAccountStore {
        private LedgerAccount account;

        private InMemoryAccounts(LedgerAccount account) {
            this.account = account;
        }

        @Override
        public Optional<LedgerAccount> findById(LedgerAccountId id) {
            return Optional.of(account).filter(value -> value.id().equals(id));
        }

        @Override
        public LedgerAccount save(LedgerAccount account) {
            this.account = account;
            return account;
        }
    }
}
