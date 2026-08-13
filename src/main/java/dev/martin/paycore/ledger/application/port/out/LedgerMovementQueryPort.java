package dev.martin.paycore.ledger.application.port.out;

import dev.martin.paycore.ledger.application.query.LedgerMovement;
import dev.martin.paycore.ledger.application.query.MovementQuery;
import java.util.List;

public interface LedgerMovementQueryPort {

    List<LedgerMovement> find(MovementQuery query);
}
