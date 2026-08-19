package dev.martin.paycore.ledger.application.account;

import dev.martin.paycore.ledger.application.port.out.LedgerAccountStore;
import dev.martin.paycore.ledger.application.port.out.LedgerBalanceStore;
import dev.martin.paycore.ledger.domain.model.LedgerAccount;
import java.util.Objects;

public final class CreateLedgerAccountService {

    private final LedgerAccountStore accounts;
    private final LedgerBalanceStore balances;

    public CreateLedgerAccountService(LedgerAccountStore accounts, LedgerBalanceStore balances) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.balances = Objects.requireNonNull(balances, "balances");
    }

    public LedgerAccount create(CreateLedgerAccountCommand command) {
        Objects.requireNonNull(command, "command");
        LedgerAccount account = accounts.save(LedgerAccount.open(
                command.id(), command.type(), command.name(), command.currency(), command.balancePolicy()));
        balances.initialize(account.id().value());
        return account;
    }
}
