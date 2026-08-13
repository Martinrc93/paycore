package dev.martin.paycore.ledger.application.account;

import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import java.util.Objects;

public record CreateLedgerAccountCommand(LedgerAccountType type, String name) {

    public CreateLedgerAccountCommand {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
    }
}
