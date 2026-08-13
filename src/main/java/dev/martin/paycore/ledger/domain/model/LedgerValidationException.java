package dev.martin.paycore.ledger.domain.model;

public class LedgerValidationException extends IllegalArgumentException {

    public LedgerValidationException(String message) {
        super(message);
    }
}
