package dev.martin.paycore.ledger.infrastructure.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LedgerReconciliationMetricsTest {

    @Test
    void recordsOnlyBoundedOutcomeMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LedgerReconciliationMetrics metrics = new LedgerReconciliationMetrics(registry);

        metrics.recordConsistent();
        metrics.recordMismatch();
        metrics.recordRebuild();

        assertThat(registry.get("paycore.ledger.balance.reconciliation")
                .tag("outcome", "consistent").counter().count()).isEqualTo(1);
        assertThat(registry.get("paycore.ledger.balance.reconciliation")
                .tag("outcome", "mismatch").counter().count()).isEqualTo(1);
        assertThat(registry.get("paycore.ledger.balance.rebuild")
                .counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .noneMatch(tag -> tag.getKey().contains("account") || tag.getKey().contains("amount")));
    }
}
