package dev.martin.paycore.ledger.infrastructure.persistence;

import dev.martin.paycore.ledger.application.balance.LedgerBalanceQuery;
import dev.martin.paycore.ledger.application.balance.LedgerAccountBalancePair;
import dev.martin.paycore.ledger.application.balance.LedgerReconciliationResult;
import dev.martin.paycore.ledger.application.port.out.LedgerBalanceStore;
import dev.martin.paycore.ledger.domain.model.FinancialTransaction;
import dev.martin.paycore.ledger.domain.model.InsufficientLedgerBalanceException;
import dev.martin.paycore.ledger.domain.model.LedgerAccountBalance;
import dev.martin.paycore.ledger.domain.model.LedgerAccountStatus;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import dev.martin.paycore.ledger.domain.model.LedgerEntryDirection;
import dev.martin.paycore.ledger.domain.model.LedgerLine;
import dev.martin.paycore.ledger.domain.model.LedgerValidationException;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.infrastructure.reconciliation.LedgerReconciliationMetrics;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Component;

@Component
public class LedgerBalancePersistenceAdapter implements LedgerBalanceStore {

    private static final String CONSISTENT = "CONSISTENT";

    private final LedgerBalanceJpaRepository balances;
    private final LedgerAccountJpaRepository accounts;
    private final LedgerReconciliationMetrics metrics;

    public LedgerBalancePersistenceAdapter(
            LedgerBalanceJpaRepository balances,
            LedgerAccountJpaRepository accounts,
            LedgerReconciliationMetrics metrics) {
        this.balances = balances;
        this.accounts = accounts;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public void initialize(UUID accountId) {
        if (balances.initializeForAccounts(List.of(accountId)) < 1
                && !balances.existsById(accountId)) {
            throw new LedgerValidationException("Every initialized account must exist");
        }
    }

    @Override
    @Transactional
    public LedgerAccountBalance find(LedgerBalanceQuery query) {
        LedgerBalanceEntity balance = balances.findById(query.accountId())
                .orElseThrow(() -> new LedgerValidationException("Every queried account must have a balance projection"));
        if (!CONSISTENT.equals(balance.consistencyStatus)) {
            throw new LedgerValidationException("Account balance projection is inconsistent");
        }
        LedgerAccountEntity account = accounts.findById(query.accountId())
                .orElseThrow(() -> new LedgerValidationException("Every queried account must exist"));
        return new LedgerAccountBalance(account.type, account.status, account.currency, account.balancePolicy,
                balance.cumulativeDebits, balance.cumulativeCredits);
    }

    @Override
    @Transactional
    public LedgerAccountBalancePair findPair(java.util.Collection<UUID> accountIds) {
        return findPair(accountIds, false);
    }

    @Override
    @Transactional
    public LedgerAccountBalancePair findPairForUpdate(java.util.Collection<UUID> accountIds) {
        return findPair(accountIds, true);
    }

    private LedgerAccountBalancePair findPair(
            java.util.Collection<UUID> accountIds, boolean forUpdate) {
        List<LedgerBalanceJpaRepository.BalancePairProjection> rows = forUpdate
                ? balances.findPairForUpdate(accountIds)
                : balances.findPair(accountIds);
        if (rows.size() != 2 || accountIds.size() != 2) {
            throw new LedgerValidationException("Every queried account must have a balance projection");
        }
        java.util.Map<UUID, LedgerBalanceJpaRepository.BalancePairProjection> byId = rows.stream()
                .collect(java.util.stream.Collectors.toMap(LedgerBalanceJpaRepository.BalancePairProjection::getAccountId,
                        value -> value));
        UUID availableId = accountIds.iterator().next();
        UUID reservedId = accountIds.stream().skip(1).findFirst().orElseThrow();
        return new LedgerAccountBalancePair(pairBalance(byId.get(availableId)), pairBalance(byId.get(reservedId)));
    }

    private static LedgerAccountBalance pairBalance(LedgerBalanceJpaRepository.BalancePairProjection row) {
        if (row == null || !CONSISTENT.equals(row.getConsistencyStatus())) {
            throw new LedgerValidationException("Account balance projection is inconsistent");
        }
        return new LedgerAccountBalance(
                LedgerAccountType.valueOf(row.getAccountType()),
                LedgerAccountStatus.valueOf(row.getAccountStatus()),
                CurrencyCode.valueOf(row.getCurrency()),
                LedgerBalancePolicy.valueOf(row.getBalancePolicy()),
                row.getCumulativeDebits(), row.getCumulativeCredits());
    }

    @Override
    @Transactional
    public LedgerReconciliationResult reconcile(UUID accountId) {
        LedgerBalanceEntity projection = balances.findByAccountIdForUpdate(accountId)
                .orElseThrow(() -> new LedgerValidationException("Every reconciled account must have a balance projection"));
        LedgerBalanceJpaRepository.ConfirmedLineTotals confirmed = confirmedTotals(accountId);
        boolean consistent = projection.cumulativeDebits.compareTo(confirmed.getDebits()) == 0
                && projection.cumulativeCredits.compareTo(confirmed.getCredits()) == 0;
        if (!consistent) {
            if (balances.replace(accountId, projection.cumulativeDebits, projection.cumulativeCredits, "INCONSISTENT") != 1) {
                throw new IllegalStateException("Balance projection disappeared");
            }
            metrics.recordMismatch();
        } else {
            metrics.recordConsistent();
        }
        return result(accountId, projection, confirmed, consistent);
    }

    @Override
    @Transactional
    public LedgerReconciliationResult rebuild(UUID accountId) {
        LedgerBalanceEntity projection = balances.findByAccountIdForUpdate(accountId)
                .orElseThrow(() -> new LedgerValidationException("Every rebuilt account must have a balance projection"));
        LedgerBalanceJpaRepository.ConfirmedLineTotals confirmed = confirmedTotals(accountId);
        if (balances.replace(accountId, confirmed.getDebits(), confirmed.getCredits(), CONSISTENT) != 1) {
            throw new IllegalStateException("Balance projection disappeared");
        }
        metrics.recordRebuild();
        return new LedgerReconciliationResult(
                accountId,
                confirmed.getDebits(),
                confirmed.getCredits(),
                confirmed.getDebits(),
                confirmed.getCredits(),
                true);
    }

    @Override
    public void lockAndValidate(FinancialTransaction transaction) {
        Map<UUID, Delta> deltas = aggregate(transaction);
        List<UUID> accountIds = new ArrayList<>(deltas.keySet());
        accountIds.sort(UUID::compareTo);
        balances.initializeForAccounts(accountIds);

        List<LedgerBalanceEntity> lockedBalances = balances.findAllByAccountIdsForUpdate(accountIds);
        Map<UUID, LedgerAccountEntity> accountById = new LinkedHashMap<>();
        accounts.findAllById(accountIds).forEach(account -> accountById.put(account.id, account));
        if (lockedBalances.size() != accountIds.size() || accountById.size() != accountIds.size()) {
            throw new LedgerValidationException("Every posting account must have a balance projection");
        }

        for (LedgerBalanceEntity balance : lockedBalances) {
            LedgerAccountEntity account = accountById.get(balance.accountId);
            if (account.status != LedgerAccountStatus.OPEN) {
                throw new LedgerValidationException("Account " + balance.accountId + " is not open");
            }
            if (account.currency != transaction.currency()) {
                throw new LedgerValidationException("Line currency must match account currency");
            }
            if (!CONSISTENT.equals(balance.consistencyStatus)) {
                throw new LedgerValidationException("Account balance projection is inconsistent");
            }

            Delta delta = deltas.get(balance.accountId);
            LedgerAccountBalance current = new LedgerAccountBalance(
                    account.type, account.status, account.currency, account.balancePolicy,
                    balance.cumulativeDebits, balance.cumulativeCredits);
            LedgerAccountBalance resulting = new LedgerAccountBalance(
                    account.type, account.status, account.currency, account.balancePolicy,
                    current.debits().add(delta.debits()),
                    current.credits().add(delta.credits()));
            if (account.balancePolicy == LedgerBalancePolicy.NON_NEGATIVE
                    && resulting.naturalBalance().signum() < 0) {
                throw new InsufficientLedgerBalanceException();
            }
        }
    }

    @Override
    public void apply(FinancialTransaction transaction) {
        Map<UUID, Delta> deltas = aggregate(transaction);
        deltas.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Delta delta = entry.getValue();
                    if (balances.increment(entry.getKey(), delta.debits(), delta.credits()) != 1) {
                        throw new IllegalStateException("Balance projection disappeared");
                    }
                });
    }

    private static Map<UUID, Delta> aggregate(FinancialTransaction transaction) {
        Map<UUID, Delta> deltas = new LinkedHashMap<>();
        for (LedgerLine line : transaction.lines()) {
            UUID accountId = line.accountId().value();
            Delta lineDelta = line.direction() == LedgerEntryDirection.DEBIT
                    ? new Delta(line.money().amount(), BigDecimal.ZERO)
                    : new Delta(BigDecimal.ZERO, line.money().amount());
            deltas.merge(accountId, lineDelta, Delta::add);
        }
        return deltas;
    }

    private LedgerBalanceJpaRepository.ConfirmedLineTotals confirmedTotals(UUID accountId) {
        return balances.aggregateConfirmedLines(accountId)
                .orElseGet(() -> new EmptyConfirmedLineTotals(accountId));
    }

    private static LedgerReconciliationResult result(
            UUID accountId,
            LedgerBalanceEntity projection,
            LedgerBalanceJpaRepository.ConfirmedLineTotals confirmed,
            boolean consistent) {
        return new LedgerReconciliationResult(
                accountId,
                projection.cumulativeDebits,
                projection.cumulativeCredits,
                confirmed.getDebits(),
                confirmed.getCredits(),
                consistent);
    }

    private record EmptyConfirmedLineTotals(UUID accountId)
            implements LedgerBalanceJpaRepository.ConfirmedLineTotals {

        @Override
        public UUID getAccountId() {
            return accountId;
        }

        @Override
        public BigDecimal getDebits() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getCredits() {
            return BigDecimal.ZERO;
        }
    }

    private record Delta(BigDecimal debits, BigDecimal credits) {

        private Delta add(Delta other) {
            return new Delta(debits.add(other.debits), credits.add(other.credits));
        }
    }
}
