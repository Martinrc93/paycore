package dev.martin.paycore.ledger.application.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.ledger.application.port.out.LedgerAccountPort;
import dev.martin.paycore.ledger.application.port.out.LedgerTransactionStore;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerAccount;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.LedgerValidationException;
import dev.martin.paycore.ledger.domain.model.LedgerEntryDirection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostLedgerTransactionServiceTest {

    private final LedgerAccountId debitAccount = LedgerAccountId.newId();
    private final LedgerAccountId creditAccount = LedgerAccountId.newId();
    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final InMemoryTransactions transactions = new InMemoryTransactions();
    private final PostLedgerTransactionService service = new PostLedgerTransactionService(accounts, transactions);

    @Test
    void postsWhenAllReferencedAccountsAreOpen() {
        accounts.add(LedgerAccount.open(debitAccount, LedgerAccountType.ASSET, "cash"));
        accounts.add(LedgerAccount.open(creditAccount, LedgerAccountType.LIABILITY, "payable"));

        FinancialPostingResult result = service.post(command("key-1"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.transaction().lines()).hasSize(2);
        assertThat(transactions.saved).hasSize(1);
    }

    @Test
    void rejectsBlockedAccountBeforePersistence() {
        accounts.add(LedgerAccount.open(debitAccount, LedgerAccountType.ASSET, "cash").block());
        accounts.add(LedgerAccount.open(creditAccount, LedgerAccountType.LIABILITY, "payable"));

        assertThatThrownBy(() -> service.post(command("key-1")))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("not open");
        assertThat(transactions.saved).isEmpty();
    }

    @Test
    void equivalentRetryUsesOriginalTransaction() {
        accounts.add(LedgerAccount.open(debitAccount, LedgerAccountType.ASSET, "cash"));
        accounts.add(LedgerAccount.open(creditAccount, LedgerAccountType.LIABILITY, "payable"));

        FinancialPostingResult first = service.post(command("key-1"));
        FinancialPostingResult second = service.post(command("key-1"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.transaction().id()).isEqualTo(first.transaction().id());
        assertThat(transactions.saved).hasSize(1);
    }

    @Test
    void rejectsSameKeyWithDifferentContent() {
        accounts.add(LedgerAccount.open(debitAccount, LedgerAccountType.ASSET, "cash"));
        accounts.add(LedgerAccount.open(creditAccount, LedgerAccountType.LIABILITY, "payable"));

        service.post(command("key-1"));

        assertThatThrownBy(() -> service.post(commandWithAmount("key-1", "11.00")))
                .isInstanceOf(LedgerIdempotencyConflictException.class);
        assertThat(transactions.saved).hasSize(1);
    }

    @Test
    void equivalentNumericFormattingDoesNotConflictOnRetry() {
        accounts.add(LedgerAccount.open(debitAccount, LedgerAccountType.ASSET, "cash"));
        accounts.add(LedgerAccount.open(creditAccount, LedgerAccountType.LIABILITY, "payable"));

        service.post(commandWithAmount("key-equivalent", "10.00"));
        FinancialPostingResult replay = service.post(commandWithAmount("key-equivalent", "10"));

        assertThat(replay.replayed()).isTrue();
        assertThat(transactions.saved).hasSize(1);
    }

    private PostLedgerTransactionCommand command(String key) {
        return commandWithAmount(key, "10.00");
    }

    private PostLedgerTransactionCommand commandWithAmount(String key, String amount) {
        return new PostLedgerTransactionCommand(
                Instant.parse("2026-08-13T12:00:00Z"),
                LocalDate.of(2026, 8, 13),
                key,
                "operation-1",
                java.util.List.of(
                        new PostingLineCommand(1, debitAccount.value(), amount, CurrencyCode.ARS, LedgerEntryDirection.DEBIT),
                        new PostingLineCommand(2, creditAccount.value(), amount, CurrencyCode.ARS, LedgerEntryDirection.CREDIT)));
    }

    private static final class InMemoryAccounts implements LedgerAccountPort {
        private final Map<LedgerAccountId, LedgerAccount> values = new HashMap<>();

        void add(LedgerAccount account) {
            values.put(account.id(), account);
        }

        @Override
        public Optional<LedgerAccount> findById(LedgerAccountId id) {
            return Optional.ofNullable(values.get(id));
        }
    }

    private static final class InMemoryTransactions implements LedgerTransactionStore {
        private final Map<String, StoredPosting> values = new HashMap<>();
        private final java.util.List<dev.martin.paycore.ledger.domain.model.FinancialTransaction> saved = new java.util.ArrayList<>();

        @Override
        public FinancialPostingResult findIdempotent(String key, String fingerprint) {
            StoredPosting existing = values.get(key);
            if (existing == null) {
                return null;
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new LedgerIdempotencyConflictException();
            }
            return new FinancialPostingResult(existing.transaction(), true);
        }

        @Override
        public FinancialPostingResult post(dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction, String fingerprint) {
            StoredPosting existing = values.get(transaction.idempotencyKey());
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    throw new LedgerIdempotencyConflictException();
                }
                return new FinancialPostingResult(existing.transaction(), true);
            }
            values.put(transaction.idempotencyKey(), new StoredPosting(transaction, fingerprint));
            saved.add(transaction);
            return new FinancialPostingResult(transaction, false);
        }

        @Override
        public Optional<dev.martin.paycore.ledger.domain.model.FinancialTransaction> findById(UUID id) {
            return values.values().stream().map(StoredPosting::transaction)
                    .filter(transaction -> transaction.id().equals(id)).findFirst();
        }
    }

    private record StoredPosting(dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction, String fingerprint) {
    }
}
