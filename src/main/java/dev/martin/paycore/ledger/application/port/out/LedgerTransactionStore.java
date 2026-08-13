package dev.martin.paycore.ledger.application.port.out;

import dev.martin.paycore.ledger.application.posting.FinancialPostingResult;
import dev.martin.paycore.ledger.domain.model.FinancialTransaction;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionStore extends LedgerTransactionRetryPort {

    FinancialPostingResult post(FinancialTransaction transaction, String fingerprint);

    Optional<FinancialTransaction> findById(UUID id);
}
