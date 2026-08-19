package dev.martin.paycore.ledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LedgerAccountTest {

    @Test
    void storesExplicitCurrencyAndBalancePolicy() {
        LedgerAccount account = LedgerAccount.open(
                LedgerAccountId.newId(), LedgerAccountType.LIABILITY, "wallet-available",
                CurrencyCode.USD, LedgerBalancePolicy.NON_NEGATIVE);
        LedgerAccount genericAccount = account(LedgerAccountType.ASSET, "cash");

        assertThat(account.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(account.balancePolicy()).isEqualTo(LedgerBalancePolicy.NON_NEGATIVE);
        assertThat(genericAccount.balancePolicy()).isEqualTo(LedgerBalancePolicy.ALLOW_NEGATIVE);
    }

    @ParameterizedTest
    @MethodSource("naturalBalanceCases")
    void calculatesNaturalBalanceForEveryAccountingType(
            LedgerAccountType type, String debits, String credits, String expected) {
        LedgerAccountBalance balance = new LedgerAccountBalance(
                type, new BigDecimal(debits), new BigDecimal(credits));

        assertThat(balance.naturalBalance()).isEqualByComparingTo(expected);
    }

    @Test
    void rejectsNegativeCumulativeDebits() {
        assertThatThrownBy(() -> new LedgerAccountBalance(
                LedgerAccountType.ASSET, new BigDecimal("-0.01"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cumulative debits and credits cannot be negative");
    }

    @Test
    void rejectsNegativeCumulativeCredits() {
        assertThatThrownBy(() -> new LedgerAccountBalance(
                LedgerAccountType.ASSET, BigDecimal.ZERO, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cumulative debits and credits cannot be negative");
    }

    @Test
    void allowsBlockingAndClosingAnOpenAccount() {
        LedgerAccount account = account(LedgerAccountType.ASSET, "cash");

        assertThat(account.block().status()).isEqualTo(LedgerAccountStatus.BLOCKED);
        assertThat(account.close().status()).isEqualTo(LedgerAccountStatus.CLOSED);
    }

    @Test
    void rejectsReopeningBlockedOrClosedAccounts() {
        LedgerAccount blocked = account(LedgerAccountType.ASSET, "cash").block();
        LedgerAccount closed = account(LedgerAccountType.ASSET, "cash").close();

        assertThatThrownBy(blocked::open).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(closed::open).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closesBlockedAccountWithoutChangingItsIdentity() {
        LedgerAccount account = account(LedgerAccountType.ASSET, "cash");

        LedgerAccount closed = account.block().close();

        assertThat(closed.id()).isEqualTo(account.id());
        assertThat(closed.type()).isEqualTo(account.type());
        assertThat(closed.status()).isEqualTo(LedgerAccountStatus.CLOSED);
    }

    private static LedgerAccount account(LedgerAccountType type, String name) {
        return LedgerAccount.open(
                LedgerAccountId.newId(), type, name, CurrencyCode.ARS, LedgerBalancePolicy.ALLOW_NEGATIVE);
    }

    private static Stream<Arguments> naturalBalanceCases() {
        return Stream.of(
                Arguments.of(LedgerAccountType.ASSET, "125.00", "25.00", "100.00"),
                Arguments.of(LedgerAccountType.EXPENSE, "125.00", "25.00", "100.00"),
                Arguments.of(LedgerAccountType.LIABILITY, "25.00", "125.00", "100.00"),
                Arguments.of(LedgerAccountType.EQUITY, "25.00", "125.00", "100.00"),
                Arguments.of(LedgerAccountType.REVENUE, "25.00", "125.00", "100.00"));
    }
}
