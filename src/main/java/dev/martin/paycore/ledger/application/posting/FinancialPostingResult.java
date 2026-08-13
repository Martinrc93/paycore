package dev.martin.paycore.ledger.application.posting;

import dev.martin.paycore.ledger.domain.model.FinancialTransaction;

public record FinancialPostingResult(FinancialTransaction transaction, boolean replayed) {
}
