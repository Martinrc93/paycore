package dev.martin.paycore.ledger.application.balance;

import dev.martin.paycore.ledger.application.port.out.LedgerBalanceStore;
import java.util.Objects;
import java.util.UUID;

public final class ReconcileLedgerBalancesService {

    private final LedgerBalanceStore balances;

    public ReconcileLedgerBalancesService(LedgerBalanceStore balances) {
        this.balances = Objects.requireNonNull(balances, "balances");
    }

    public LedgerReconciliationResult reconcile(UUID accountId) {
        return balances.reconcile(Objects.requireNonNull(accountId, "accountId"));
    }
}
