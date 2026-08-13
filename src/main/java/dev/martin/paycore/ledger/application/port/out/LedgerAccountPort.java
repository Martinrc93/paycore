package dev.martin.paycore.ledger.application.port.out;

import dev.martin.paycore.ledger.domain.model.LedgerAccount;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import java.util.Optional;

public interface LedgerAccountPort {

    Optional<LedgerAccount> findById(LedgerAccountId id);
}
