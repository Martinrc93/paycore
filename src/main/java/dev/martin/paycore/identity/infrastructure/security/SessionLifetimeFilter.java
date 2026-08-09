package dev.martin.paycore.identity.infrastructure.security;

import dev.martin.paycore.identity.application.authentication.SessionLifetimePolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SessionLifetimeFilter extends OncePerRequestFilter {

    private final SessionLifetimePolicy lifetimePolicy;
    private final RequestMatcher publicRequests;

    public SessionLifetimeFilter(SessionLifetimePolicy lifetimePolicy, RequestMatcher publicRequests) {
        this.lifetimePolicy = lifetimePolicy;
        this.publicRequests = publicRequests;
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

        HttpSession session = request.getSession(false);
        Object value = session == null ? null
                : session.getAttribute(CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE);
        if (!(value instanceof Instant authenticatedAt)) {
            invalidate(session);
            SecurityResponses.unauthorized(response);
            return;
        }

        Duration remainingIdleTimeout = lifetimePolicy.remainingIdleTimeout(authenticatedAt);
        if (remainingIdleTimeout.isZero()) {
            invalidate(session);
            SecurityResponses.unauthorized(response);
            return;
        }
        session.setMaxInactiveInterval(Math.toIntExact(roundUpToSeconds(remainingIdleTimeout)));
        filterChain.doFilter(request, response);
    }

    private static long roundUpToSeconds(Duration duration) {
        return duration.getNano() == 0 ? duration.getSeconds() : Math.addExact(duration.getSeconds(), 1);
    }

    private static void invalidate(HttpSession session) {
        SecurityContextHolder.clearContext();
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // The session was already invalidated by another request.
            }
        }
    }
}
