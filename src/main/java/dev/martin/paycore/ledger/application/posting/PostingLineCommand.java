package dev.martin.paycore.ledger.application.posting;

import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerEntryDirection;
import java.util.UUID;

public record PostingLineCommand(
        int sequence,
        UUID accountId,
        String amount,
        CurrencyCode currency,
        LedgerEntryDirection direction) {
}
