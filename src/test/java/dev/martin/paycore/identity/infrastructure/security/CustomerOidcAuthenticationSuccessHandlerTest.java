package dev.martin.paycore.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.application.authentication.ResolveCustomerAccess;
import dev.martin.paycore.identity.application.authentication.SessionLifetimePolicy;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import dev.martin.paycore.wallet.application.query.WalletAccess;
import dev.martin.paycore.wallet.application.query.WalletView;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import dev.martin.paycore.wallet.domain.model.WalletId;
import dev.martin.paycore.wallet.domain.model.WalletStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

class CustomerOidcAuthenticationSuccessHandlerTest {

    private static final CustomerId CUSTOMER_ID = new CustomerId(
            UUID.fromString("70000000-0000-0000-0000-000000000007"));
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Test
    void invalidatesTheNewSessionWhenCustomerBecomesIneligibleDuringCallback() throws Exception {
        AtomicInteger statusReads = new AtomicInteger();
        ResolveCustomerAccess customerAccess = new ResolveCustomerAccess() {
            @Override
            public Optional<CustomerAccess> resolve(ExternalIdentity identity) {
                return Optional.empty();
            }

            @Override
            public Optional<CustomerAccess> resolve(CustomerId customerId) {
                CustomerStatus status = statusReads.incrementAndGet() == 1
                        ? CustomerStatus.ACTIVE : CustomerStatus.BLOCKED;
                return Optional.of(new CustomerAccess(customerId, status));
            }
        };
        CustomerOidcAuthenticationSuccessHandler handler = new CustomerOidcAuthenticationSuccessHandler(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SessionLifetimePolicy(Clock.fixed(NOW, ZoneOffset.UTC)),
                "/signed-in",
                customerAccess, completeWallets());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                new CustomerPrincipal(CUSTOMER_ID.value()), List.of(), "paycore");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"forbidden\"}");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void rejectsAnAlreadyIneligibleCustomerWithoutRetainingTheAuthorizationSession() throws Exception {
        ResolveCustomerAccess customerAccess = new ResolveCustomerAccess() {
            @Override
            public Optional<CustomerAccess> resolve(ExternalIdentity identity) {
                return Optional.empty();
            }

            @Override
            public Optional<CustomerAccess> resolve(CustomerId customerId) {
                return Optional.of(new CustomerAccess(customerId, CustomerStatus.BLOCKED));
            }
        };
        CustomerOidcAuthenticationSuccessHandler handler = new CustomerOidcAuthenticationSuccessHandler(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SessionLifetimePolicy(Clock.fixed(NOW, ZoneOffset.UTC)),
                "/signed-in",
                customerAccess, completeWallets());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute("oauth2-authorization-request", "state");
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                new CustomerPrincipal(CUSTOMER_ID.value()), List.of(), "paycore");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void rejectsActiveCustomerWithoutACompleteWalletBeforeCreatingASession() throws Exception {
        ResolveCustomerAccess customerAccess = new ResolveCustomerAccess() {
            @Override
            public Optional<CustomerAccess> resolve(ExternalIdentity identity) {
                return Optional.empty();
            }

            @Override
            public Optional<CustomerAccess> resolve(CustomerId customerId) {
                return Optional.of(new CustomerAccess(customerId, CustomerStatus.ACTIVE));
            }
        };
        CustomerOidcAuthenticationSuccessHandler handler = new CustomerOidcAuthenticationSuccessHandler(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SessionLifetimePolicy(Clock.fixed(NOW, ZoneOffset.UTC)),
                "/signed-in", customerAccess, new WalletAccess() {
                    @Override
                    public WalletView query(UUID customerId) {
                        throw new IllegalArgumentException("wallet incomplete");
                    }

                    @Override
                    public Optional<WalletView> confirmCompleteUsdWallet(UUID customerId) {
                        return Optional.empty();
                    }
                });
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                new CustomerPrincipal(CUSTOMER_ID.value()), List.of(), "paycore");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"forbidden\"}");
        assertThat(request.getSession(false)).isNull();
    }

    private static WalletAccess completeWallets() {
        return new WalletAccess() {
            @Override
            public WalletView query(UUID customerId) {
                return new WalletView(WalletId.newId(), WalletStatus.UNFUNDED, WalletCurrency.USD,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            }

            @Override
            public Optional<WalletView> confirmCompleteUsdWallet(UUID customerId) {
                return Optional.of(query(customerId));
            }
        };
    }
}
