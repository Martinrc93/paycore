package dev.martin.paycore.identity.application.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;

import dev.martin.paycore.identity.application.port.out.CustomerAccessRepository;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ResolveCustomerAccessServiceTest {

    private static final CustomerId FIRST_CUSTOMER_ID =
            new CustomerId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final CustomerId SECOND_CUSTOMER_ID =
            new CustomerId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

    private InMemoryCustomerAccessRepository repository;
    private ResolveCustomerAccess service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCustomerAccessRepository();
        service = new ResolveCustomerAccessService(repository);
    }

    @Test
    void resolvesLinkedActiveCustomerByExactIssuerAndSubject() {
        ExternalIdentity identity = new ExternalIdentity("https://issuer.example/realms/paycore", "subject-123");
        repository.link(identity, new CustomerAccess(FIRST_CUSTOMER_ID, CustomerStatus.ACTIVE));

        Optional<CustomerAccess> result = service.resolve(identity);

        assertThat(result).contains(new CustomerAccess(FIRST_CUSTOMER_ID, CustomerStatus.ACTIVE));
        assertThat(result.orElseThrow().isActive()).isTrue();
    }

    @Test
    void distinguishesTheSameSubjectUnderDifferentIssuers() {
        ExternalIdentity firstIssuer = new ExternalIdentity("https://issuer-one.example", "shared-subject");
        ExternalIdentity secondIssuer = new ExternalIdentity("https://issuer-two.example", "shared-subject");
        repository.link(firstIssuer, new CustomerAccess(FIRST_CUSTOMER_ID, CustomerStatus.ACTIVE));
        repository.link(secondIssuer, new CustomerAccess(SECOND_CUSTOMER_ID, CustomerStatus.ACTIVE));

        assertThat(service.resolve(firstIssuer).orElseThrow().customerId()).isEqualTo(FIRST_CUSTOMER_ID);
        assertThat(service.resolve(secondIssuer).orElseThrow().customerId()).isEqualTo(SECOND_CUSTOMER_ID);
    }

    @Test
    void doesNotResolveAnUnknownIdentityLink() {
        assertThat(service.resolve(new ExternalIdentity("https://issuer.example", "unknown-subject"))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = CustomerStatus.class, mode = EXCLUDE, names = "ACTIVE")
    void preservesEveryInactiveStatusAndDeniesAccess(CustomerStatus status) {
        ExternalIdentity identity = new ExternalIdentity("https://issuer.example", "subject-123");
        repository.link(identity, new CustomerAccess(FIRST_CUSTOMER_ID, status));

        CustomerAccess result = service.resolve(identity).orElseThrow();

        assertThat(result.customerId()).isEqualTo(FIRST_CUSTOMER_ID);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.isActive()).isFalse();
    }

    @Test
    void resolvesByCustomerIdWithTheCurrentStatus() {
        repository.save(new CustomerAccess(FIRST_CUSTOMER_ID, CustomerStatus.ACTIVE));
        assertThat(service.resolve(FIRST_CUSTOMER_ID).orElseThrow().isActive()).isTrue();

        repository.save(new CustomerAccess(FIRST_CUSTOMER_ID, CustomerStatus.SUSPENDED));

        CustomerAccess result = service.resolve(FIRST_CUSTOMER_ID).orElseThrow();
        assertThat(result.customerId()).isEqualTo(FIRST_CUSTOMER_ID);
        assertThat(result.status()).isEqualTo(CustomerStatus.SUSPENDED);
        assertThat(result.isActive()).isFalse();
    }

    @Test
    void changingAnEmailClaimDoesNotAffectIdentityResolution() {
        ExternalIdentity identity = new ExternalIdentity("https://issuer.example", "subject-123");
        repository.link(identity, new CustomerAccess(FIRST_CUSTOMER_ID, CustomerStatus.ACTIVE));
        Map<String, String> claims = new HashMap<>(Map.of(
                "iss", "https://issuer.example",
                "sub", "subject-123",
                "email", "old@example.com"));

        CustomerId beforeChange = service.resolve(identityFrom(claims)).orElseThrow().customerId();
        claims.put("email", "new@example.com");
        CustomerId afterChange = service.resolve(identityFrom(claims)).orElseThrow().customerId();

        assertThat(beforeChange).isEqualTo(FIRST_CUSTOMER_ID);
        assertThat(afterChange).isEqualTo(FIRST_CUSTOMER_ID);
    }

    @Test
    void rejectsBlankIdentityComponents() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ExternalIdentity(" ", "subject-123"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ExternalIdentity("https://issuer.example", " "));
    }

    private static ExternalIdentity identityFrom(Map<String, String> claims) {
        return new ExternalIdentity(claims.get("iss"), claims.get("sub"));
    }

    private static final class InMemoryCustomerAccessRepository implements CustomerAccessRepository {

        private final Map<ExternalIdentity, CustomerAccess> byIdentity = new HashMap<>();
        private final Map<CustomerId, CustomerAccess> byCustomerId = new HashMap<>();

        void link(ExternalIdentity identity, CustomerAccess access) {
            byIdentity.put(identity, access);
            save(access);
        }

        void save(CustomerAccess access) {
            byCustomerId.put(access.customerId(), access);
        }

        @Override
        public Optional<CustomerAccess> findByExternalIdentity(ExternalIdentity identity) {
            return Optional.ofNullable(byIdentity.get(identity));
        }

        @Override
        public Optional<CustomerAccess> findByCustomerId(CustomerId customerId) {
            return Optional.ofNullable(byCustomerId.get(customerId));
        }
    }
}
