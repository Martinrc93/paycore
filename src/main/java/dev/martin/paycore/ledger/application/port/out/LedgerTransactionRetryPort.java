package dev.martin.paycore.ledger.application.port.out;

import dev.martin.paycore.ledger.application.posting.FinancialPostingResult;

public interface LedgerTransactionRetryPort {

    FinancialPostingResult findIdempotent(String idempotencyKey, String fingerprint);
}
