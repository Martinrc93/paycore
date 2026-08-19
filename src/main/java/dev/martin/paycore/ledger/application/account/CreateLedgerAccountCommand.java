package dev.martin.paycore.ledger.application.account;

import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import java.util.Objects;

public record CreateLedgerAccountCommand(
        LedgerAccountId id,
        LedgerAccountType type,
        String name,
        CurrencyCode currency,
        LedgerBalancePolicy balancePolicy) {

    public CreateLedgerAccountCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(balancePolicy, "balancePolicy");
    }
}
