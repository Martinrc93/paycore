package dev.martin.paycore.wallet.infrastructure.ledger;

import dev.martin.paycore.ledger.application.account.ChangeLedgerAccountStatusService;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.wallet.application.port.out.WalletAccountLifecycle;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LedgerAccountLifecycleAdapter implements WalletAccountLifecycle {

    private final ChangeLedgerAccountStatusService accounts;

    public LedgerAccountLifecycleAdapter(ChangeLedgerAccountStatusService accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override
    public void block(UUID availableAccountId, UUID reservedAccountId) {
        accounts.block(new LedgerAccountId(availableAccountId));
        accounts.block(new LedgerAccountId(reservedAccountId));
    }

    @Override
    public void unblock(UUID availableAccountId, UUID reservedAccountId) {
        accounts.unblock(new LedgerAccountId(availableAccountId));
        accounts.unblock(new LedgerAccountId(reservedAccountId));
    }

    @Override
    public void close(UUID availableAccountId, UUID reservedAccountId) {
        accounts.close(new LedgerAccountId(availableAccountId));
        accounts.close(new LedgerAccountId(reservedAccountId));
    }
}
