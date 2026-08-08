package dev.martin.paycore.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-08T10:00:00Z");

    @Test
    void createsIndividualInProvisioning() {
        Customer customer = Customer.register(
                new CustomerId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                Email.of("person@example.com"), CustomerType.INDIVIDUAL, CREATED_AT);

        assertThat(customer.status()).isEqualTo(CustomerStatus.PROVISIONING);
        assertThat(customer.type()).isEqualTo(CustomerType.INDIVIDUAL);
        assertThat(customer.createdAt()).isEqualTo(CREATED_AT);
        assertThat(customer.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void createsBusinessInProvisioning() {
        Customer customer = newCustomer(CustomerType.BUSINESS);

        assertThat(customer.type()).isEqualTo(CustomerType.BUSINESS);
        assertThat(customer.status()).isEqualTo(CustomerStatus.PROVISIONING);
    }

    @Test
    void activatesProvisioningCustomer() {
        Customer customer = newCustomer(CustomerType.INDIVIDUAL);
        Instant activatedAt = Instant.parse("2026-08-08T10:05:00Z");

        customer.activate(activatedAt);

        assertThat(customer.status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(customer.updatedAt()).isEqualTo(activatedAt);
    }

    @Test
    void marksProvisioningFailure() {
        Customer customer = newCustomer(CustomerType.INDIVIDUAL);

        customer.failProvisioning(Instant.parse("2026-08-08T10:06:00Z"));

        assertThat(customer.status()).isEqualTo(CustomerStatus.PROVISIONING_FAILED);
    }

    @Test
    void rejectsTransitionsFromTerminalRegistrationState() {
        Customer customer = newCustomer(CustomerType.INDIVIDUAL);
        customer.activate(Instant.parse("2026-08-08T10:05:00Z"));

        assertThatThrownBy(() -> customer.activate(Instant.parse("2026-08-08T10:06:00Z")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> customer.failProvisioning(Instant.parse("2026-08-08T10:06:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonUtcFreeInstantAlternativesByUsingInstantOnly() throws Exception {
        assertThat(Customer.class.getDeclaredMethod("createdAt").getReturnType()).isEqualTo(Instant.class);
        assertThat(Customer.class.getDeclaredMethod("updatedAt").getReturnType()).isEqualTo(Instant.class);
    }

    private static Customer newCustomer(CustomerType type) {
        return Customer.register(
                new CustomerId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                Email.of("person@example.com"), type, CREATED_AT);
    }
}
