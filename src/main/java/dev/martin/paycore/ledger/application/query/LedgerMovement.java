package dev.martin.paycore.ledger.application.query;

import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerEntryDirection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LedgerMovement(
        UUID transactionId,
        UUID accountId,
        int sequence,
        Instant postedAt,
        LocalDate valueDate,
        String operationReference,
        BigDecimal amount,
        CurrencyCode currency,
        LedgerEntryDirection direction) {
}
