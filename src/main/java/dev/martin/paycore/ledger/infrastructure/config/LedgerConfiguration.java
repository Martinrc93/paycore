package dev.martin.paycore.ledger.infrastructure.config;

import dev.martin.paycore.ledger.application.port.out.LedgerAccountPort;
import dev.martin.paycore.ledger.application.port.out.LedgerAccountStore;
import dev.martin.paycore.ledger.application.port.out.LedgerMovementQueryPort;
import dev.martin.paycore.ledger.application.port.out.LedgerTransactionStore;
import dev.martin.paycore.ledger.application.posting.PostLedgerTransactionService;
import dev.martin.paycore.ledger.application.posting.CompensateLedgerTransactionService;
import dev.martin.paycore.ledger.application.account.CreateLedgerAccountService;
import dev.martin.paycore.ledger.application.account.ChangeLedgerAccountStatusService;
import dev.martin.paycore.ledger.application.query.QueryLedgerMovementsService;
import dev.martin.paycore.ledger.application.balance.QueryLedgerBalancesService;
import dev.martin.paycore.ledger.application.balance.RebuildLedgerBalancesService;
import dev.martin.paycore.ledger.application.balance.ReconcileLedgerBalancesService;
import dev.martin.paycore.ledger.application.port.out.LedgerBalanceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LedgerConfiguration {

    @Bean
    PostLedgerTransactionService postLedgerTransactionService(
            LedgerAccountPort accounts, LedgerTransactionStore transactions) {
        return new PostLedgerTransactionService(accounts, transactions);
    }

    @Bean
    CompensateLedgerTransactionService compensateLedgerTransactionService(
            LedgerTransactionStore transactions, PostLedgerTransactionService posting) {
        return new CompensateLedgerTransactionService(transactions, posting);
    }

    @Bean
    QueryLedgerMovementsService queryLedgerMovementsService(LedgerMovementQueryPort movements) {
        return new QueryLedgerMovementsService(movements);
    }

    @Bean
    CreateLedgerAccountService createLedgerAccountService(LedgerAccountStore accounts,
            LedgerBalanceStore ledgerBalanceStore) {
        return new CreateLedgerAccountService(accounts, ledgerBalanceStore);
    }

    @Bean
    ChangeLedgerAccountStatusService changeLedgerAccountStatusService(LedgerAccountStore accounts) {
        return new ChangeLedgerAccountStatusService(accounts);
    }

    @Bean
    QueryLedgerBalancesService queryLedgerBalancesService(LedgerBalanceStore balances) {
        return new QueryLedgerBalancesService(balances);
    }

    @Bean
    ReconcileLedgerBalancesService reconcileLedgerBalancesService(LedgerBalanceStore balances) {
        return new ReconcileLedgerBalancesService(balances);
    }

    @Bean
    RebuildLedgerBalancesService rebuildLedgerBalancesService(LedgerBalanceStore balances) {
        return new RebuildLedgerBalancesService(balances);
    }
}
