package dev.martin.paycore.ledger.application.query;

import dev.martin.paycore.ledger.application.port.out.LedgerMovementQueryPort;
import java.util.List;
import java.util.Objects;

public final class QueryLedgerMovementsService {

    private final LedgerMovementQueryPort movements;

    public QueryLedgerMovementsService(LedgerMovementQueryPort movements) {
        this.movements = Objects.requireNonNull(movements, "movements");
    }

    public List<LedgerMovement> find(MovementQuery query) {
        return List.copyOf(movements.find(Objects.requireNonNull(query, "query")));
    }
}
