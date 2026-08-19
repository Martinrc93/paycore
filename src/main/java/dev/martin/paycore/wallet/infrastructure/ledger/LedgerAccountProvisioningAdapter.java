package dev.martin.paycore.wallet.infrastructure.ledger;

import dev.martin.paycore.ledger.application.account.CreateLedgerAccountCommand;
import dev.martin.paycore.ledger.application.account.CreateLedgerAccountService;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import dev.martin.paycore.wallet.application.port.out.LedgerAccountProvisioner;
import dev.martin.paycore.wallet.application.port.out.WalletAccountProvisioning;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class LedgerAccountProvisioningAdapter implements LedgerAccountProvisioner {

    private final CreateLedgerAccountService accounts;

    public LedgerAccountProvisioningAdapter(CreateLedgerAccountService accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override
    public void provision(WalletAccountProvisioning provisioning) {
        create(provisioning.availableAccountId(), provisioning.customerId(), "available");
        create(provisioning.reservedAccountId(), provisioning.customerId(), "reserved");
    }

    private void create(java.util.UUID accountId, java.util.UUID customerId, String purpose) {
        accounts.create(new CreateLedgerAccountCommand(
                new LedgerAccountId(accountId), LedgerAccountType.LIABILITY,
                "wallet:" + customerId + ":" + purpose, CurrencyCode.USD,
                LedgerBalancePolicy.NON_NEGATIVE));
    }
}
