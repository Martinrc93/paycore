package dev.martin.paycore.ledger.application.port.out;

import dev.martin.paycore.ledger.application.balance.LedgerBalanceQuery;
import dev.martin.paycore.ledger.application.balance.LedgerAccountBalancePair;
import dev.martin.paycore.ledger.application.balance.LedgerReconciliationResult;
import dev.martin.paycore.ledger.domain.model.FinancialTransaction;
import dev.martin.paycore.ledger.domain.model.LedgerAccountBalance;
import java.util.UUID;

public interface LedgerBalanceStore {

    void initialize(UUID accountId);

    LedgerAccountBalance find(LedgerBalanceQuery query);

    LedgerAccountBalancePair findPair(java.util.Collection<UUID> accountIds);

    LedgerAccountBalancePair findPairForUpdate(java.util.Collection<UUID> accountIds);

    LedgerReconciliationResult reconcile(UUID accountId);

    LedgerReconciliationResult rebuild(UUID accountId);

    void lockAndValidate(FinancialTransaction transaction);

    void apply(FinancialTransaction transaction);
}
