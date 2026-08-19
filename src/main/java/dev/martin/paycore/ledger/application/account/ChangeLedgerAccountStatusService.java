package dev.martin.paycore.ledger.application.account;

import dev.martin.paycore.ledger.application.port.out.LedgerAccountStore;
import dev.martin.paycore.ledger.domain.model.LedgerAccount;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class ChangeLedgerAccountStatusService {

    private final LedgerAccountStore accounts;

    public ChangeLedgerAccountStatusService(LedgerAccountStore accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    public LedgerAccount block(LedgerAccountId accountId) {
        return change(accountId, LedgerAccount::block);
    }

    public LedgerAccount unblock(LedgerAccountId accountId) {
        return change(accountId, LedgerAccount::unblock);
    }

    public LedgerAccount close(LedgerAccountId accountId) {
        return change(accountId, LedgerAccount::close);
    }

    private LedgerAccount change(LedgerAccountId accountId, UnaryOperator<LedgerAccount> operation) {
        Objects.requireNonNull(accountId, "accountId");
        LedgerAccount account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger account not found"));
        return accounts.save(operation.apply(account));
    }
}
