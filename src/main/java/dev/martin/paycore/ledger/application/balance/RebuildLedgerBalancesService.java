package dev.martin.paycore.ledger.application.balance;

import dev.martin.paycore.ledger.application.port.out.LedgerBalanceStore;
import java.util.Objects;
import java.util.UUID;

public final class RebuildLedgerBalancesService {

    private final LedgerBalanceStore balances;

    public RebuildLedgerBalancesService(LedgerBalanceStore balances) {
        this.balances = Objects.requireNonNull(balances, "balances");
    }

    public LedgerReconciliationResult rebuild(UUID accountId) {
        return balances.rebuild(Objects.requireNonNull(accountId, "accountId"));
    }
}
