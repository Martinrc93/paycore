package dev.martin.paycore.ledger.application.balance;

import dev.martin.paycore.ledger.application.port.out.LedgerBalanceStore;
import dev.martin.paycore.ledger.domain.model.LedgerAccountBalance;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

public final class QueryLedgerBalancesService {

    private final LedgerBalanceStore balances;

    public QueryLedgerBalancesService(LedgerBalanceStore balances) {
        this.balances = Objects.requireNonNull(balances, "balances");
    }

    public LedgerAccountBalance find(LedgerBalanceQuery query) {
        return balances.find(Objects.requireNonNull(query, "query"));
    }

    public LedgerAccountBalancePair findPair(UUID availableAccountId, UUID reservedAccountId) {
        return findPair(List.of(availableAccountId, reservedAccountId), false);
    }

    public LedgerAccountBalancePair findPairForUpdate(UUID availableAccountId, UUID reservedAccountId) {
        return findPair(List.of(availableAccountId, reservedAccountId), true);
    }

    private LedgerAccountBalancePair findPair(List<UUID> accountIds, boolean forUpdate) {
        UUID availableAccountId = accountIds.get(0);
        UUID reservedAccountId = accountIds.get(1);
        Objects.requireNonNull(availableAccountId, "availableAccountId");
        Objects.requireNonNull(reservedAccountId, "reservedAccountId");
        if (availableAccountId.equals(reservedAccountId)) {
            throw new IllegalArgumentException("Balance accounts must be distinct");
        }
        return forUpdate
                ? balances.findPairForUpdate(accountIds)
                : balances.findPair(accountIds);
    }
}
