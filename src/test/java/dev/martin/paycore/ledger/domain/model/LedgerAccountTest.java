package dev.martin.paycore.ledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LedgerAccountTest {

    @Test
    void allowsBlockingAndClosingAnOpenAccount() {
        LedgerAccount account = LedgerAccount.open(LedgerAccountId.newId(), LedgerAccountType.ASSET, "cash");

        assertThat(account.block().status()).isEqualTo(LedgerAccountStatus.BLOCKED);
        assertThat(account.close().status()).isEqualTo(LedgerAccountStatus.CLOSED);
    }

    @Test
    void rejectsReopeningBlockedOrClosedAccounts() {
        LedgerAccount blocked = LedgerAccount.open(LedgerAccountId.newId(), LedgerAccountType.ASSET, "cash").block();
        LedgerAccount closed = LedgerAccount.open(LedgerAccountId.newId(), LedgerAccountType.ASSET, "cash").close();

        assertThatThrownBy(blocked::open).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(closed::open).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closesBlockedAccountWithoutChangingItsIdentity() {
        LedgerAccount account = LedgerAccount.open(LedgerAccountId.newId(), LedgerAccountType.ASSET, "cash");

        LedgerAccount closed = account.block().close();

        assertThat(closed.id()).isEqualTo(account.id());
        assertThat(closed.type()).isEqualTo(account.type());
        assertThat(closed.status()).isEqualTo(LedgerAccountStatus.CLOSED);
    }
}
