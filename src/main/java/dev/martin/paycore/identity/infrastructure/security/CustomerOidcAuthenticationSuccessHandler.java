package dev.martin.paycore.identity.infrastructure.security;

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
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

public final class CustomerOidcAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    public static final String AUTHENTICATED_AT_ATTRIBUTE = "paycore.authenticated-at";

    private final Clock clock;
    private final SessionLifetimePolicy lifetimePolicy;
    private final String successUri;

    CustomerOidcAuthenticationSuccessHandler(Clock clock, SessionLifetimePolicy lifetimePolicy, String successUri) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifetimePolicy = Objects.requireNonNull(lifetimePolicy, "lifetimePolicy");
        this.successUri = Objects.requireNonNull(successUri, "successUri");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        HttpSession session = request.getSession();
        java.time.Instant authenticatedAt = clock.instant();
        session.setAttribute(AUTHENTICATED_AT_ATTRIBUTE, authenticatedAt);
        session.setMaxInactiveInterval(Math.toIntExact(
                roundUpToSeconds(lifetimePolicy.remainingIdleTimeout(authenticatedAt))));
        response.sendRedirect(successUri);
    }

    private static long roundUpToSeconds(Duration duration) {
        return duration.getNano() == 0 ? duration.getSeconds() : Math.addExact(duration.getSeconds(), 1);
    }
}
