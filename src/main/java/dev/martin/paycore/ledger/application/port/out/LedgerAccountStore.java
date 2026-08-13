package dev.martin.paycore.ledger.application.port.out;

import dev.martin.paycore.ledger.domain.model.LedgerAccount;

public interface LedgerAccountStore extends LedgerAccountPort {

    LedgerAccount save(LedgerAccount account);
}
