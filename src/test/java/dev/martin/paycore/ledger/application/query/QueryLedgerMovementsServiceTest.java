package dev.martin.paycore.ledger.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.ledger.application.port.out.LedgerMovementQueryPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryLedgerMovementsServiceTest {

    @Test
    void returnsAnImmutableCopyFromTheQueryPort() {
        List<LedgerMovement> values = new java.util.ArrayList<>();
        QueryLedgerMovementsService service = new QueryLedgerMovementsService(query -> values);

        List<LedgerMovement> result = service.find(new MovementQuery(UUID.randomUUID(), 0, 10));

        assertThat(result).isUnmodifiable();
    }

    @Test
    void boundsMovementQuerySize() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new MovementQuery(UUID.randomUUID(), 0, 501))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
