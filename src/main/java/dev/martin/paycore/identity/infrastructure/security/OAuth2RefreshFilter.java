package dev.martin.paycore.identity.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class OAuth2RefreshFilter extends OncePerRequestFilter {

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final RequestMatcher publicRequests;
    private final AuthenticationMetrics metrics;

    public OAuth2RefreshFilter(OAuth2AuthorizedClientManager authorizedClientManager,
            OAuth2AuthorizedClientRepository authorizedClients, RequestMatcher publicRequests,
            AuthenticationMetrics metrics) {
        this.authorizedClientManager = authorizedClientManager;
        this.authorizedClients = authorizedClients;
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
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2Authentication)) {
            reject(request, response, authentication, null);
            return;
        }

        String registrationId = oauth2Authentication.getAuthorizedClientRegistrationId();
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                .principal(authentication)
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build();
        try {
            OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
            if (authorizedClient == null) {
                reject(request, response, authentication, registrationId);
                return;
            }
        } catch (OAuth2AuthorizationException exception) {
            reject(request, response, authentication, registrationId);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication, String registrationId) throws IOException {
        metrics.refreshFailure();
        if (registrationId != null) {
            try {
                authorizedClients.removeAuthorizedClient(registrationId, authentication, request, response);
            } catch (IllegalStateException ignored) {
                // A manager failure handler may already have invalidated the session.
            }
        }
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // The session is already invalid.
            }
        }
        SecurityResponses.unauthorized(response);
    }
}
