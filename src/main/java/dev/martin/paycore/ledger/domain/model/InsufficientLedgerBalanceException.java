package dev.martin.paycore.ledger.domain.model;

public class InsufficientLedgerBalanceException extends LedgerValidationException {

    public InsufficientLedgerBalanceException() {
        super("Ledger balance would become negative");
    }
}
