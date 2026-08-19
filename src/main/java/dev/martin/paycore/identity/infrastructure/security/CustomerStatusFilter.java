package dev.martin.paycore.identity.infrastructure.security;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.application.authentication.ResolveCustomerAccess;
import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.wallet.application.query.WalletAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class CustomerStatusFilter extends OncePerRequestFilter {

    private final ResolveCustomerAccess customerAccess;
    private final WalletAccess wallets;
    private final SessionRevocationPort sessions;
    private final RequestMatcher publicRequests;
    private final AuthenticationMetrics metrics;

    public CustomerStatusFilter(ResolveCustomerAccess customerAccess, WalletAccess wallets,
            SessionRevocationPort sessions,
            RequestMatcher publicRequests, AuthenticationMetrics metrics) {
        this.customerAccess = customerAccess;
        this.wallets = wallets;
        this.sessions = sessions;
        this.publicRequests = publicRequests;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return publicRequests.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!(authentication.getPrincipal() instanceof CustomerPrincipal principal)) {
            invalidate(request.getSession(false));
            SecurityResponses.unauthorized(response);
            return;
        }

        CustomerId customerId = new CustomerId(principal.customerId());
        boolean active = customerAccess.resolve(customerId)
                .filter(CustomerAccess::isActive)
                .filter(ignored -> hasCompleteWallet(customerId))
                .isPresent();
        if (!active) {
            metrics.customerAccessDenied();
            sessions.revokeAll(customerId);
            invalidate(request.getSession(false));
            SecurityResponses.forbidden(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean hasCompleteWallet(CustomerId customerId) {
        try {
            return wallets.confirmCompleteUsdWallet(customerId.value()).isPresent();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static void invalidate(HttpSession session) {
        SecurityContextHolder.clearContext();
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // Indexed revocation may already have removed the current session.
            }
        }
    }
}
