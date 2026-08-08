package dev.martin.paycore.identity.infrastructure.security;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.application.authentication.ResolveCustomerAccess;
import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class CustomerStatusFilter extends OncePerRequestFilter {

    private final ResolveCustomerAccess customerAccess;
    private final SessionRevocationPort sessions;

    public CustomerStatusFilter(ResolveCustomerAccess customerAccess, SessionRevocationPort sessions) {
        this.customerAccess = customerAccess;
        this.sessions = sessions;
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
        boolean active = customerAccess.resolve(customerId).filter(CustomerAccess::isActive).isPresent();
        if (!active) {
            sessions.revokeAll(customerId);
            invalidate(request.getSession(false));
            SecurityResponses.forbidden(response);
            return;
        }
        filterChain.doFilter(request, response);
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
