package dev.martin.paycore.ledger.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
class LedgerTransactionLineId implements Serializable {

    @Column(name = "transaction_id")
    UUID transactionId;

    @Column(name = "line_sequence")
    int sequence;

    protected LedgerTransactionLineId() {
    }

    LedgerTransactionLineId(UUID transactionId, int sequence) {
        this.transactionId = transactionId;
        this.sequence = sequence;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LedgerTransactionLineId that)) {
            return false;
        }
        return sequence == that.sequence && Objects.equals(transactionId, that.transactionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, sequence);
    }
}
