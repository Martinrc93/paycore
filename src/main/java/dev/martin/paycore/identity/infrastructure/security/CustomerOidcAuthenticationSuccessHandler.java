package dev.martin.paycore.identity.infrastructure.security;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.application.authentication.ResolveCustomerAccess;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.application.authentication.SessionLifetimePolicy;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Clock;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

public final class CustomerOidcAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    public static final String AUTHENTICATED_AT_ATTRIBUTE = "paycore.authenticated-at";

    private final Clock clock;
    private final SessionLifetimePolicy lifetimePolicy;
    private final String successUri;
    private final ResolveCustomerAccess customerAccess;

    CustomerOidcAuthenticationSuccessHandler(Clock clock, SessionLifetimePolicy lifetimePolicy, String successUri,
            ResolveCustomerAccess customerAccess) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifetimePolicy = Objects.requireNonNull(lifetimePolicy, "lifetimePolicy");
        this.successUri = Objects.requireNonNull(successUri, "successUri");
        this.customerAccess = Objects.requireNonNull(customerAccess, "customerAccess");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
        Authentication authentication) throws IOException, ServletException {
        if (!isActive(authentication)) {
            reject(request, response);
            return;
        }
        HttpSession session = request.getSession();
        java.time.Instant authenticatedAt = clock.instant();
        session.setAttribute(AUTHENTICATED_AT_ATTRIBUTE, authenticatedAt);
        session.setMaxInactiveInterval(Math.toIntExact(
                roundUpToSeconds(lifetimePolicy.remainingIdleTimeout(authenticatedAt))));
        if (!isActive(authentication)) {
            reject(request, response);
            return;
        }
        response.sendRedirect(successUri);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // The session may already have been removed by status revocation.
            }
        }
        SecurityResponses.forbidden(response);
    }

    private boolean isActive(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof CustomerPrincipal principal)) {
            return false;
        }
        return customerAccess.resolve(new CustomerId(principal.customerId()))
                .map(CustomerAccess::isActive)
                .orElse(false);
    }

    private static long roundUpToSeconds(Duration duration) {
        return duration.getNano() == 0 ? duration.getSeconds() : Math.addExact(duration.getSeconds(), 1);
    }
}
