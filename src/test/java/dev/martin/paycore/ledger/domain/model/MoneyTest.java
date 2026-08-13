package dev.martin.paycore.ledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalizesSupportedCurrencyScale() {
        Money money = Money.of("10", CurrencyCode.ARS);

        assertThat(money.amount()).isEqualByComparingTo("10.00");
        assertThat(money.amount().scale()).isEqualTo(2);
    }

    @Test
    void acceptsZeroDecimalCurrencyOnlyWithoutFraction() {
        assertThat(Money.of("100", CurrencyCode.JPY).amount()).isEqualByComparingTo("100");
        assertThatThrownBy(() -> Money.of("100.1", CurrencyCode.JPY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveAndOverPreciseAmounts() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ZERO, CurrencyCode.USD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("-1.00", CurrencyCode.USD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.001", CurrencyCode.EUR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmountsThatDoNotFitThePersistencePrecision() {
        assertThatThrownBy(() -> Money.of("123456789012345678.00", CurrencyCode.ARS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThatThrownBy(() -> CurrencyCode.of("GBP"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
