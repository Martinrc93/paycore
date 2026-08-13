package dev.martin.paycore.ledger.application.posting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PostLedgerTransactionCommand(
        Instant postedAt,
        LocalDate valueDate,
        String idempotencyKey,
        String operationReference,
        List<PostingLineCommand> lines,
        UUID reversalOf,
        UUID correctionOf) {

    public PostLedgerTransactionCommand(
            Instant postedAt,
            LocalDate valueDate,
            String idempotencyKey,
            String operationReference,
            List<PostingLineCommand> lines) {
        this(postedAt, valueDate, idempotencyKey, operationReference, lines, null, null);
    }

    public PostLedgerTransactionCommand {
        Objects.requireNonNull(postedAt, "postedAt");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(operationReference, "operationReference");
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
        if (reversalOf != null && correctionOf != null) {
            throw new IllegalArgumentException("Posting cannot be both reversal and correction");
        }
    }
}
