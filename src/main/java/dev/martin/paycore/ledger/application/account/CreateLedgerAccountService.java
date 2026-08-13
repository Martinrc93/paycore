package dev.martin.paycore.ledger.application.account;

import dev.martin.paycore.ledger.application.port.out.LedgerAccountStore;
import dev.martin.paycore.ledger.domain.model.LedgerAccount;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import java.util.Objects;

public final class CreateLedgerAccountService {

    private final LedgerAccountStore accounts;

    public CreateLedgerAccountService(LedgerAccountStore accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    public LedgerAccount create(CreateLedgerAccountCommand command) {
        Objects.requireNonNull(command, "command");
        return accounts.save(LedgerAccount.open(LedgerAccountId.newId(), command.type(), command.name()));
    }
}
