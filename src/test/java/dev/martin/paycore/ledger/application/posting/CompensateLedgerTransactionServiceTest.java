package dev.martin.paycore.ledger.application.posting;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.ledger.application.port.out.LedgerAccountPort;
import dev.martin.paycore.ledger.application.port.out.LedgerTransactionStore;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.FinancialTransaction;
import dev.martin.paycore.ledger.domain.model.LedgerAccount;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import dev.martin.paycore.ledger.domain.model.LedgerEntryDirection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompensateLedgerTransactionServiceTest {

    @Test
    void postsAReversalAsANewTransaction() {
        LedgerAccountId debit = LedgerAccountId.newId();
        LedgerAccountId credit = LedgerAccountId.newId();
        InMemoryStore store = new InMemoryStore();
        store.accounts.put(debit, account(debit, LedgerAccountType.ASSET, "cash"));
        store.accounts.put(credit, account(credit, LedgerAccountType.LIABILITY, "payable"));
        PostLedgerTransactionService posting = new PostLedgerTransactionService(store, store);
        FinancialTransaction original = FinancialTransaction.confirm(
                Instant.parse("2026-08-13T12:00:00Z"), LocalDate.of(2026, 8, 13), "original", "op", List.of(
                        dev.martin.paycore.ledger.domain.model.LedgerLine.debit(1, debit, dev.martin.paycore.ledger.domain.model.Money.of("10.00", CurrencyCode.ARS)),
                        dev.martin.paycore.ledger.domain.model.LedgerLine.credit(2, credit, dev.martin.paycore.ledger.domain.model.Money.of("10.00", CurrencyCode.ARS))));
        store.transactions.put(original.id(), original);
        CompensateLedgerTransactionService compensation = new CompensateLedgerTransactionService(store, posting);

        FinancialPostingResult result = compensation.reverse(
                original.id(), Instant.parse("2026-08-13T12:01:00Z"), LocalDate.of(2026, 8, 13), "reversal", "op-reversal");

        assertThat(result.transaction().reversalOf()).isEqualTo(original.id());
        assertThat(result.transaction().id()).isNotEqualTo(original.id());
    }

    private static final class InMemoryStore implements LedgerAccountPort, LedgerTransactionStore {
        private final Map<LedgerAccountId, LedgerAccount> accounts = new HashMap<>();
        private final Map<UUID, FinancialTransaction> transactions = new HashMap<>();

        @Override
        public Optional<LedgerAccount> findById(LedgerAccountId id) {
            return Optional.ofNullable(accounts.get(id));
        }

        @Override
        public FinancialPostingResult findIdempotent(String key, String fingerprint) {
            return null;
        }

        @Override
        public FinancialPostingResult post(FinancialTransaction transaction, String fingerprint) {
            transactions.put(transaction.id(), transaction);
            return new FinancialPostingResult(transaction, false);
        }

        @Override
        public Optional<FinancialTransaction> findById(UUID id) {
            return Optional.ofNullable(transactions.get(id));
        }
    }

    private static LedgerAccount account(LedgerAccountId id, LedgerAccountType type, String name) {
        return LedgerAccount.open(id, type, name, CurrencyCode.ARS, LedgerBalancePolicy.ALLOW_NEGATIVE);
    }
}
