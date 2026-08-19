package dev.martin.paycore.identity.application.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.identity.application.port.out.CustomerAccessRepository;
import dev.martin.paycore.identity.application.port.out.CustomerActivationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthenticateCustomerServiceTest {

    private static final CustomerId CUSTOMER_ID =
            new CustomerId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final ExternalIdentity IDENTITY =
            new ExternalIdentity("https://issuer.example/realms/paycore", "subject-123");
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    private InMemoryCustomerAccessRepository accessRepository;
    private InMemoryCustomerActivationPort activationPort;
    private AuthenticateCustomerService service;

    @BeforeEach
    void setUp() {
        accessRepository = new InMemoryCustomerAccessRepository();
        activationPort = new InMemoryCustomerActivationPort();
        service = new AuthenticateCustomerService(accessRepository, activationPort);
    }

    @Test
    void resolvesLinkedActiveCustomer() {
        accessRepository.link(IDENTITY, new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE));
        activationPort.activeResult = Optional.of(new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE));

        assertThat(service.authenticate(new VerifiedCustomerLogin(IDENTITY, false, NOW)))
                .contains(new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE));
        assertThat(activationPort.activeCalls).isEqualTo(1);
        assertThat(activationPort.customerId).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void activatesLinkedPendingCustomerWithVerifiedEmail() {
        accessRepository.link(IDENTITY, new CustomerAccess(CUSTOMER_ID, CustomerStatus.PENDING_VERIFICATION));
        activationPort.result = Optional.of(new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE));

        assertThat(service.authenticate(new VerifiedCustomerLogin(IDENTITY, true, NOW)))
                .contains(new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE));
        assertThat(activationPort.customerId).isEqualTo(CUSTOMER_ID);
        assertThat(activationPort.activatedAt).isEqualTo(NOW);
    }

    @Test
    void deniesPendingCustomerWithoutVerifiedEmail() {
        accessRepository.link(IDENTITY, new CustomerAccess(CUSTOMER_ID, CustomerStatus.PENDING_VERIFICATION));

        assertThat(service.authenticate(new VerifiedCustomerLogin(IDENTITY, false, NOW))).isEmpty();
        assertThat(activationPort.calls).isZero();
    }

    @Test
    void deniesLinkedActiveCustomerWithoutACompleteWallet() {
        accessRepository.link(IDENTITY, new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE));

        assertThat(service.authenticate(new VerifiedCustomerLogin(IDENTITY, false, NOW))).isEmpty();
        assertThat(activationPort.activeCalls).isEqualTo(1);
    }

    @Test
    void deniesUnknownIdentity() {
        assertThat(service.authenticate(new VerifiedCustomerLogin(IDENTITY, true, NOW))).isEmpty();
        assertThat(activationPort.calls).isZero();
    }

    @Test
    void deniesSuspendedAndBlockedCustomers() {
        for (CustomerStatus status : new CustomerStatus[] {CustomerStatus.SUSPENDED, CustomerStatus.BLOCKED}) {
            accessRepository.link(IDENTITY, new CustomerAccess(CUSTOMER_ID, status));

            assertThat(service.authenticate(new VerifiedCustomerLogin(IDENTITY, true, NOW))).isEmpty();
        }
        assertThat(activationPort.calls).isZero();
    }

    @Test
    void concurrentVerifiedLoginsConvergeOnActiveCustomer() throws Exception {
        accessRepository.link(IDENTITY, new CustomerAccess(CUSTOMER_ID, CustomerStatus.PENDING_VERIFICATION));
        activationPort.result = Optional.of(new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<CustomerAccess>> first = executor.submit(() -> authenticateAfter(start));
            Future<Optional<CustomerAccess>> second = executor.submit(() -> authenticateAfter(start));
            start.countDown();

            assertThat(first.get()).contains(new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE));
            assertThat(second.get()).contains(new CustomerAccess(CUSTOMER_ID, CustomerStatus.ACTIVE));
        }
    }

    private Optional<CustomerAccess> authenticateAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        return service.authenticate(new VerifiedCustomerLogin(IDENTITY, true, NOW));
    }

    private static final class InMemoryCustomerAccessRepository implements CustomerAccessRepository {
        private final Map<ExternalIdentity, CustomerAccess> byIdentity = new HashMap<>();

        void link(ExternalIdentity identity, CustomerAccess access) {
            byIdentity.put(identity, access);
        }

        @Override
        public Optional<CustomerAccess> findByExternalIdentity(ExternalIdentity identity) {
            return Optional.ofNullable(byIdentity.get(identity));
        }

        @Override
        public Optional<CustomerAccess> findByCustomerId(CustomerId customerId) {
            return byIdentity.values().stream().filter(access -> access.customerId().equals(customerId)).findFirst();
        }
    }

    private static final class InMemoryCustomerActivationPort implements CustomerActivationPort {
        private int calls;
        private CustomerId customerId;
        private Instant activatedAt;
        private int activeCalls;
        private Optional<CustomerAccess> result = Optional.empty();
        private Optional<CustomerAccess> activeResult = Optional.empty();

        @Override
        public Optional<CustomerAccess> activatePending(CustomerId customerId, Instant activatedAt) {
            calls++;
            this.customerId = customerId;
            this.activatedAt = activatedAt;
            return result;
        }

        @Override
        public Optional<CustomerAccess> confirmActive(CustomerId customerId) {
            activeCalls++;
            this.customerId = customerId;
            return activeResult;
        }
    }
}
