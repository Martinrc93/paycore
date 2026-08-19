package dev.martin.paycore.ledger.infrastructure.reconciliation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class LedgerReconciliationMetrics {

    private final Counter consistent;
    private final Counter mismatch;
    private final Counter rebuild;

    public LedgerReconciliationMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        consistent = registry.counter("paycore.ledger.balance.reconciliation", "outcome", "consistent");
        mismatch = registry.counter("paycore.ledger.balance.reconciliation", "outcome", "mismatch");
        rebuild = registry.counter("paycore.ledger.balance.rebuild");
    }

    public void recordConsistent() {
        consistent.increment();
    }

    public void recordMismatch() {
        mismatch.increment();
    }

    public void recordRebuild() {
        rebuild.increment();
    }
}
