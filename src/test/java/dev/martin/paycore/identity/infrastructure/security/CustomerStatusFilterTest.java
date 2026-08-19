package dev.martin.paycore.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.application.authentication.ResolveCustomerAccess;
import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import dev.martin.paycore.wallet.application.query.WalletAccess;
import dev.martin.paycore.wallet.application.query.WalletView;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import dev.martin.paycore.wallet.domain.model.WalletId;
import dev.martin.paycore.wallet.domain.model.WalletStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;

class CustomerStatusFilterTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsActiveCustomerWithoutCompleteWalletAndInvalidatesSession() throws Exception {
        AtomicBoolean chainCalled = new AtomicBoolean();
        AtomicBoolean revoked = new AtomicBoolean();
        CustomerStatusFilter filter = new CustomerStatusFilter(
                activeCustomer(), emptyWallets(), sessions(revoked), publicRequests(), metrics());
        MockHttpServletRequest request = requestWithCustomer();
        request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (request1, response1) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"forbidden\"}");
        assertThat(chainCalled).isFalse();
        assertThat(revoked).isTrue();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void admitsActiveCustomerOnlyAfterWalletContractConfirmsCompleteness() throws Exception {
        AtomicBoolean chainCalled = new AtomicBoolean();
        CustomerStatusFilter filter = new CustomerStatusFilter(
                activeCustomer(), completeWallets(), sessions(new AtomicBoolean()), publicRequests(), metrics());
        MockHttpServletRequest request = requestWithCustomer();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (request1, response1) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static ResolveCustomerAccess activeCustomer() {
        return new ResolveCustomerAccess() {
            @Override
            public Optional<CustomerAccess> resolve(ExternalIdentity identity) {
                return Optional.empty();
            }

            @Override
            public Optional<CustomerAccess> resolve(CustomerId customerId) {
                return Optional.of(new CustomerAccess(customerId, CustomerStatus.ACTIVE));
            }
        };
    }

    private static WalletAccess emptyWallets() {
        return new WalletAccess() {
            @Override
            public WalletView query(UUID customerId) {
                throw new IllegalArgumentException("missing wallet");
            }

            @Override
            public Optional<WalletView> confirmCompleteUsdWallet(UUID customerId) {
                return Optional.empty();
            }
        };
    }

    private static WalletAccess completeWallets() {
        return new WalletAccess() {
            @Override
            public WalletView query(UUID customerId) {
                return view(customerId);
            }

            @Override
            public Optional<WalletView> confirmCompleteUsdWallet(UUID customerId) {
                return Optional.of(view(customerId));
            }
        };
    }

    private static WalletView view(UUID customerId) {
        return new WalletView(WalletId.newId(), WalletStatus.UNFUNDED, WalletCurrency.USD,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static SessionRevocationPort sessions(AtomicBoolean revoked) {
        return new SessionRevocationPort() {
            @Override
            public void revokeCurrent(String sessionId) {
            }

            @Override
            public void revokeAll(CustomerId customerId) {
                revoked.set(true);
            }
        };
    }

    private static RequestMatcher publicRequests() {
        return request -> false;
    }

    private static AuthenticationMetrics metrics() {
        return new AuthenticationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static MockHttpServletRequest requestWithCustomer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        SecurityContextHolder.getContext().setAuthentication(new OAuth2AuthenticationToken(
                new CustomerPrincipal(CUSTOMER_ID), List.of(), "paycore"));
        return request;
    }
}
