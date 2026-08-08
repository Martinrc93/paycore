package dev.martin.paycore.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailTest {

    @Test
    void canonicalizesSupportedAddress() {
        Email email = Email.of("  Customer.Name+tag@Example.COM  ");

        assertThat(email.value()).isEqualTo("customer.name+tag@example.com");
    }

    @Test
    void acceptsMaximumSupportedLengths() {
        String local = "a".repeat(64);
        String domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);

        assertThat(Email.of(local + "@" + domain).value()).hasSize(254);
    }

    @Test
    void rejectsUnsupportedAddresses() {
        assertThatThrownBy(() -> Email.of("two..dots@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Email.of("customer example@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Email.of("\"quoted\"@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Email.of("josé@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Email.of("customer@localhost"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Email.of("a".repeat(65) + "@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Email.of("a@" + "b".repeat(249) + ".com"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
