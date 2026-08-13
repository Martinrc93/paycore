package dev.martin.paycore.ledger.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record FinancialTransaction(
        UUID id,
        Instant postedAt,
        LocalDate valueDate,
        String idempotencyKey,
        String operationReference,
        List<LedgerLine> lines,
        UUID reversalOf,
        UUID correctionOf) {

    private static final int MAX_REFERENCE_LENGTH = 128;

    public FinancialTransaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(postedAt, "postedAt");
        Objects.requireNonNull(valueDate, "valueDate");
        idempotencyKey = requireReference(idempotencyKey, "idempotencyKey");
        operationReference = requireReference(operationReference, "operationReference");
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new LedgerValidationException("Transaction must contain lines");
        }
        if (reversalOf != null && correctionOf != null) {
            throw new LedgerValidationException("Transaction cannot be both reversal and correction");
        }
        validateLines(lines);
    }

    public static FinancialTransaction confirm(
            Instant postedAt,
            LocalDate valueDate,
            String idempotencyKey,
            String operationReference,
            List<LedgerLine> lines) {
        return confirm(postedAt, valueDate, idempotencyKey, operationReference, lines, null, null);
    }

    public static FinancialTransaction confirm(
            Instant postedAt,
            LocalDate valueDate,
            String idempotencyKey,
            String operationReference,
            List<LedgerLine> lines,
            UUID reversalOf,
            UUID correctionOf) {
        return new FinancialTransaction(
                UUID.randomUUID(), postedAt, valueDate, idempotencyKey, operationReference, lines, reversalOf, correctionOf);
    }

    public Money total() {
        BigDecimal debitTotal = lines.stream()
                .filter(line -> line.direction() == LedgerEntryDirection.DEBIT)
                .map(line -> line.money().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Money(debitTotal, currency());
    }

    public CurrencyCode currency() {
        return lines.getFirst().money().currency();
    }

    public FinancialTransaction reverse(
            Instant reversalPostedAt,
            LocalDate reversalValueDate,
            String reversalIdempotencyKey,
            String reversalOperationReference) {
        return new FinancialTransaction(
                UUID.randomUUID(),
                reversalPostedAt,
                reversalValueDate,
                reversalIdempotencyKey,
                reversalOperationReference,
                lines.stream().map(LedgerLine::oppositeDirection).toList(),
                id,
                null);
    }

    public FinancialTransaction correct(
            Instant correctionPostedAt,
            LocalDate correctionValueDate,
            String correctionIdempotencyKey,
            String correctionOperationReference,
            List<LedgerLine> correctionLines) {
        return new FinancialTransaction(
                UUID.randomUUID(),
                correctionPostedAt,
                correctionValueDate,
                correctionIdempotencyKey,
                correctionOperationReference,
                correctionLines,
                null,
                id);
    }

    private static void validateLines(List<LedgerLine> lines) {
        CurrencyCode currency = lines.getFirst().money().currency();
        boolean debit = false;
        boolean credit = false;
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        long distinctSequences = lines.stream().map(LedgerLine::sequence).distinct().count();
        if (distinctSequences != lines.size()) {
            throw new LedgerValidationException("Line sequences must be unique");
        }
        for (LedgerLine line : lines) {
            if (line.money().currency() != currency) {
                throw new LedgerValidationException("Transaction lines must use one currency");
            }
            if (line.direction() == LedgerEntryDirection.DEBIT) {
                debit = true;
                debitTotal = debitTotal.add(line.money().amount());
            } else {
                credit = true;
                creditTotal = creditTotal.add(line.money().amount());
            }
        }
        if (!debit || !credit || debitTotal.compareTo(creditTotal) != 0) {
            throw new LedgerValidationException("Transaction debits and credits must balance");
        }
    }

    private static String requireReference(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_REFERENCE_LENGTH) {
            throw new LedgerValidationException(field + " must contain 1 to " + MAX_REFERENCE_LENGTH + " characters");
        }
        if (normalized.matches("(?i).*\\b(password|secret|token|authorization|api[-_ ]?key|bearer)\\b.*")
                || normalized.matches("(?i).*\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b.*")) {
            throw new LedgerValidationException(field + " contains sensitive metadata");
        }
        return normalized;
    }
}
