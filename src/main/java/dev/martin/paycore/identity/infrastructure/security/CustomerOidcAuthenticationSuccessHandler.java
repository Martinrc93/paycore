package dev.martin.paycore.identity.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

final class CustomerOidcAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    static final String AUTHENTICATED_AT_ATTRIBUTE = "paycore.authenticated-at";

    private final Clock clock;
    private final String successUri;

    CustomerOidcAuthenticationSuccessHandler(Clock clock, String successUri) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.successUri = Objects.requireNonNull(successUri, "successUri");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        HttpSession session = request.getSession();
        if (session.getAttribute(AUTHENTICATED_AT_ATTRIBUTE) == null) {
            session.setAttribute(AUTHENTICATED_AT_ATTRIBUTE, clock.instant());
        }
        response.sendRedirect(successUri);
    }
}
