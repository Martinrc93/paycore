package dev.martin.paycore.identity.application.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.identity.application.port.out.CustomerRepository;
import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.Customer;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.domain.model.CustomerType;
import dev.martin.paycore.identity.domain.model.Email;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChangeCustomerStatusServiceTest {

    private static final CustomerId CUSTOMER_ID =
            new CustomerId(UUID.fromString("50000000-0000-0000-0000-000000000005"));
    private static final Instant CREATED_AT = Instant.parse("2026-08-08T10:00:00Z");
    private static final Instant CHANGED_AT = Instant.parse("2026-08-08T12:00:00Z");

    @Test
    void suspensionPersistsTheProvidedInstantBeforeRevokingEverySession() {
        List<String> events = new ArrayList<>();
        RecordingCustomerRepository customers = new RecordingCustomerRepository(activeCustomer(), events);
        RecordingRevocationPort sessions = new RecordingRevocationPort(events);
        ChangeCustomerStatusService service = service(customers, sessions);

        service.suspend(CUSTOMER_ID);

        assertThat(customers.saved.status()).isEqualTo(CustomerStatus.SUSPENDED);
        assertThat(customers.saved.updatedAt()).isEqualTo(CHANGED_AT);
        assertThat(events).containsExactly("save:SUSPENDED", "revoke-all");
    }

    @Test
    void blockingPersistsTheProvidedInstantBeforeRevokingEverySession() {
        List<String> events = new ArrayList<>();
        RecordingCustomerRepository customers = new RecordingCustomerRepository(activeCustomer(), events);
        RecordingRevocationPort sessions = new RecordingRevocationPort(events);

        service(customers, sessions).block(CUSTOMER_ID);

        assertThat(customers.saved.status()).isEqualTo(CustomerStatus.BLOCKED);
        assertThat(customers.saved.updatedAt()).isEqualTo(CHANGED_AT);
        assertThat(events).containsExactly("save:BLOCKED", "revoke-all");
    }

    @Test
    void rejectsMissingCustomersWithoutRevokingSessions() {
        List<String> events = new ArrayList<>();
        RecordingCustomerRepository customers = new RecordingCustomerRepository(null, events);
        RecordingRevocationPort sessions = new RecordingRevocationPort(events);

        assertThatThrownBy(() -> service(customers, sessions).suspend(CUSTOMER_ID))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(events).isEmpty();
    }

    @Test
    void rejectsTransitionsFromNonActiveStatesWithoutPersistingOrRevoking() {
        List<String> events = new ArrayList<>();
        Customer suspended = Customer.rehydrate(CUSTOMER_ID, Email.of("customer@example.test"),
                CustomerType.INDIVIDUAL, CustomerStatus.SUSPENDED, CREATED_AT, CREATED_AT);
        RecordingCustomerRepository customers = new RecordingCustomerRepository(suspended, events);
        RecordingRevocationPort sessions = new RecordingRevocationPort(events);

        assertThatThrownBy(() -> service(customers, sessions).block(CUSTOMER_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(events).isEmpty();
    }

    @Test
    void doesNotRevokeSessionsWhenPersistenceFails() {
        List<String> events = new ArrayList<>();
        RecordingCustomerRepository customers = new RecordingCustomerRepository(activeCustomer(), events);
        customers.failSave = true;
        RecordingRevocationPort sessions = new RecordingRevocationPort(events);

        assertThatThrownBy(() -> service(customers, sessions).suspend(CUSTOMER_ID))
                .isInstanceOf(IllegalStateException.class).hasMessage("save failed");
        assertThat(events).containsExactly("save:SUSPENDED");
    }

    private static ChangeCustomerStatusService service(CustomerRepository customers,
            SessionRevocationPort sessions) {
        return new ChangeCustomerStatusService(customers, sessions,
                Clock.fixed(CHANGED_AT, ZoneOffset.UTC));
    }

    private static Customer activeCustomer() {
        return Customer.rehydrate(CUSTOMER_ID, Email.of("customer@example.test"),
                CustomerType.INDIVIDUAL, CustomerStatus.ACTIVE, CREATED_AT, CREATED_AT);
    }

    private static final class RecordingCustomerRepository implements CustomerRepository {

        private final Customer customer;
        private final List<String> events;
        private Customer saved;
        private boolean failSave;

        private RecordingCustomerRepository(Customer customer, List<String> events) {
            this.customer = customer;
            this.events = events;
        }

        @Override
        public Optional<Customer> findById(CustomerId customerId) {
            return Optional.ofNullable(customer);
        }

        @Override
        public void save(Customer customer) {
            saved = customer;
            events.add("save:" + customer.status());
            if (failSave) {
                throw new IllegalStateException("save failed");
            }
        }
    }

    private static final class RecordingRevocationPort implements SessionRevocationPort {

        private final List<String> events;

        private RecordingRevocationPort(List<String> events) {
            this.events = events;
        }

        @Override
        public void revokeCurrent(String sessionId) {
            throw new AssertionError("Current-session revocation was not expected");
        }

        @Override
        public void revokeAll(CustomerId customerId) {
            assertThat(customerId).isEqualTo(CUSTOMER_ID);
            events.add("revoke-all");
        }
    }
}
