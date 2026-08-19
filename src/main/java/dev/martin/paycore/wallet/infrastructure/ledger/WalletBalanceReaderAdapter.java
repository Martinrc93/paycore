package dev.martin.paycore.wallet.infrastructure.ledger;

import dev.martin.paycore.ledger.application.balance.LedgerAccountBalancePair;
import dev.martin.paycore.ledger.application.balance.QueryLedgerBalancesService;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.LedgerAccountStatus;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import dev.martin.paycore.ledger.domain.model.LedgerValidationException;
import dev.martin.paycore.wallet.application.query.WalletBalanceInconsistencyException;
import dev.martin.paycore.wallet.application.port.out.WalletBalanceReader;
import dev.martin.paycore.wallet.application.port.out.WalletBalances;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WalletBalanceReaderAdapter implements WalletBalanceReader {

    private final QueryLedgerBalancesService balances;

    public WalletBalanceReaderAdapter(QueryLedgerBalancesService balances) {
        this.balances = Objects.requireNonNull(balances, "balances");
    }

    @Override
    public WalletBalances read(UUID availableAccountId, UUID reservedAccountId) {
        return readPair(availableAccountId, reservedAccountId, false);
    }

    @Override
    public WalletBalances readForUpdate(UUID availableAccountId, UUID reservedAccountId) {
        return readPair(availableAccountId, reservedAccountId, true, true);
    }

    @Override
    public WalletBalances readForUpdateForClose(UUID availableAccountId, UUID reservedAccountId) {
        return readPair(availableAccountId, reservedAccountId, true, false);
    }

    private WalletBalances readPair(UUID availableAccountId, UUID reservedAccountId, boolean forUpdate) {
        return readPair(availableAccountId, reservedAccountId, forUpdate, true);
    }

    private WalletBalances readPair(UUID availableAccountId, UUID reservedAccountId,
            boolean forUpdate, boolean requireOpenAccounts) {
        LedgerAccountBalancePair pair = forUpdate
                ? findPairForUpdate(availableAccountId, reservedAccountId)
                : findPair(availableAccountId, reservedAccountId);
        if (pair.available().accountType() != LedgerAccountType.LIABILITY
                || pair.reserved().accountType() != LedgerAccountType.LIABILITY
                || (requireOpenAccounts && (pair.available().status() != LedgerAccountStatus.OPEN
                        || pair.reserved().status() != LedgerAccountStatus.OPEN))
                || pair.available().currency() != CurrencyCode.USD
                || pair.reserved().currency() != CurrencyCode.USD
                || pair.available().balancePolicy() != LedgerBalancePolicy.NON_NEGATIVE
                || pair.reserved().balancePolicy() != LedgerBalancePolicy.NON_NEGATIVE
                || pair.available().naturalBalance().signum() < 0
                || pair.reserved().naturalBalance().signum() < 0) {
            throw new WalletBalanceInconsistencyException("Wallet balance is inconsistent");
        }
        return new WalletBalances(pair.available().naturalBalance(), pair.reserved().naturalBalance());
    }

    private LedgerAccountBalancePair findPair(UUID availableAccountId, UUID reservedAccountId) {
        try {
            return balances.findPair(availableAccountId, reservedAccountId);
        } catch (LedgerValidationException failure) {
            throw new WalletBalanceInconsistencyException("Wallet balance is inconsistent", failure);
        }
    }

    private LedgerAccountBalancePair findPairForUpdate(UUID availableAccountId, UUID reservedAccountId) {
        try {
            return balances.findPairForUpdate(availableAccountId, reservedAccountId);
        } catch (LedgerValidationException failure) {
            throw new WalletBalanceInconsistencyException("Wallet balance is inconsistent", failure);
        }
    }
}
