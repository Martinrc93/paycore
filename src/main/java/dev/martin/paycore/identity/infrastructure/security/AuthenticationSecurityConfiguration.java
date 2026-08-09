package dev.martin.paycore.identity.infrastructure.security;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.application.authentication.ResolveCustomerAccess;
import dev.martin.paycore.identity.application.authentication.ResolveCustomerAccessService;
import dev.martin.paycore.identity.application.authentication.SessionLifetimePolicy;
import dev.martin.paycore.identity.application.port.out.CustomerAccessRepository;
import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "paycore.authentication.enabled")
public class AuthenticationSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ResolveCustomerAccess resolveCustomerAccess(CustomerAccessRepository repository) {
        return new ResolveCustomerAccessService(repository);
    }

    @Bean
    SessionLifetimePolicy sessionLifetimePolicy(Clock clock) {
        return new SessionLifetimePolicy(clock);
    }

    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    RequestMatcher publicRequests() {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                new DispatcherTypeRequestMatcher(DispatcherType.ERROR),
                paths.matcher(HttpMethod.GET, "/actuator/health"),
                paths.matcher(HttpMethod.POST, "/api/customers"),
                paths.matcher(HttpMethod.GET, "/bff/auth/csrf"),
                paths.matcher(HttpMethod.GET, "/oauth2/authorization/**"),
                paths.matcher(HttpMethod.GET, "/login/oauth2/code/**"));
    }

    @Bean
    CookieSerializer sessionCookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("__Host-paycore-session");
        serializer.setCookiePath("/");
        serializer.setUseSecureCookie(true);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory() {
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> JwtValidators.createDefaultWithValidators(
                new OidcIdTokenValidator(registration),
                new OidcAudienceValidator(registration.getClientId())));
        return factory;
    }

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients) {
        OAuth2AuthorizedClientProvider refreshProvider = OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken()
                .build();
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(refreshProvider);
        manager.setAuthorizationFailureHandler((exception, authentication, attributes) -> {
            HttpServletRequest request = (HttpServletRequest) attributes.get(HttpServletRequest.class.getName());
            HttpServletResponse response = (HttpServletResponse) attributes.get(HttpServletResponse.class.getName());
            if (request != null && response != null
                    && authentication instanceof OAuth2AuthenticationToken oauth2Authentication) {
                authorizedClients.removeAuthorizedClient(
                        oauth2Authentication.getAuthorizedClientRegistrationId(), authentication, request, response);
            }
            HttpSession session = request == null ? null : request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        });
        return manager;
    }

    @Bean
    CustomerOidcAuthenticationSuccessHandler customerOidcAuthenticationSuccessHandler(
            Clock clock, SessionLifetimePolicy lifetimePolicy, AuthenticationNavigationProperties navigation) {
        return new CustomerOidcAuthenticationSuccessHandler(clock, lifetimePolicy, navigation.successUri());
    }

    @Bean
    SecurityFilterChain authenticationSecurityFilterChain(HttpSecurity http,
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            OAuth2AuthorizedClientManager authorizedClientManager,
            SecurityContextRepository securityContexts,
            ResolveCustomerAccess customerAccess,
            SessionRevocationPort sessions,
            SessionLifetimePolicy lifetimePolicy,
            CustomerOidcAuthenticationSuccessHandler successHandler,
            AuthenticationNavigationProperties navigation,
            RequestMatcher publicRequests) throws Exception {
        DefaultOAuth2AuthorizationRequestResolver authorizationRequests =
                new DefaultOAuth2AuthorizationRequestResolver(registrations);
        authorizationRequests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher registration = paths.matcher(HttpMethod.POST, "/api/customers");
        RequestMatcher authenticatedUnsafeRequest = new AndRequestMatcher(
                CsrfFilter.DEFAULT_CSRF_MATCHER,
                new NegatedRequestMatcher(registration),
                request -> isLocalCustomerAuthenticated());
        HttpSessionCsrfTokenRepository csrfTokens = new HttpSessionCsrfTokenRepository();
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        JsonAuthenticationEntryPoint authenticationEntryPoint = new JsonAuthenticationEntryPoint();
        JsonAccessDeniedHandler accessDeniedHandler = new JsonAccessDeniedHandler();
        SessionLifetimeFilter lifetimeFilter = new SessionLifetimeFilter(lifetimePolicy, publicRequests);
        CustomerStatusFilter statusFilter = new CustomerStatusFilter(customerAccess, sessions, publicRequests);
        OAuth2RefreshFilter refreshFilter =
                new OAuth2RefreshFilter(authorizedClientManager, authorizedClients, publicRequests);

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(publicRequests).permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .requireCsrfProtectionMatcher(authenticatedUnsafeRequest)
                        .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                            @Override
                            public <O extends CsrfFilter> O postProcess(O filter) {
                                filter.setAccessDeniedHandler(accessDeniedHandler);
                                return filter;
                            }
                        }))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(securityContexts))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .logout(logout -> logout
                        .logoutUrl(navigation.logoutPath())
                        .addLogoutHandler((request, response, authentication) -> {
                            HttpSession session = request.getSession(false);
                            if (session != null) {
                                request.setAttribute(CurrentSessionLogout.SESSION_ID_ATTRIBUTE, session.getId());
                            }
                            if (authentication instanceof OAuth2AuthenticationToken oauth2Authentication) {
                                authorizedClients.removeAuthorizedClient(
                                        oauth2Authentication.getAuthorizedClientRegistrationId(), authentication,
                                        request, response);
                            }
                        })
                        .logoutSuccessHandler((request, response, authentication) -> {
                            Object sessionId = request.getAttribute(CurrentSessionLogout.SESSION_ID_ATTRIBUTE);
                            if (sessionId instanceof String id) {
                                sessions.revokeCurrent(id);
                            }
                            if (!(authentication instanceof OAuth2AuthenticationToken)) {
                                SecurityResponses.unauthorized(response);
                                return;
                            }
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        }))
                .oauth2Login(oauth2 -> oauth2
                        .authorizedClientRepository(authorizedClients)
                        .securityContextRepository(securityContexts)
                        .authorizationEndpoint(endpoint ->
                                endpoint.authorizationRequestResolver(authorizationRequests))
                        .successHandler(successHandler)
                        .failureHandler(AuthenticationSecurityConfiguration::authenticationFailure)
                        .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                            @Override
                            public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationResultConverter(authentication ->
                                        localAuthentication(authentication, customerAccess));
                                return filter;
                            }
                        }))
                .addFilterAfter(lifetimeFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(statusFilter, SessionLifetimeFilter.class)
                .addFilterAfter(refreshFilter, CustomerStatusFilter.class);
        return http.build();
    }

    private static OAuth2AuthenticationToken localAuthentication(OAuth2LoginAuthenticationToken authentication,
            ResolveCustomerAccess resolver) {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        ExternalIdentity identity = new ExternalIdentity(
                oidcUser.getIssuer().toExternalForm(), oidcUser.getSubject());
        CustomerAccess access = resolver.resolve(identity)
                .filter(CustomerAccess::isActive)
                .orElseThrow(() -> new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        CustomerPrincipal principal = new CustomerPrincipal(access.customerId().value());
        return new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), authentication.getClientRegistration().getRegistrationId());
    }

    private static boolean isLocalCustomerAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomerPrincipal;
    }

    private static void authenticationFailure(HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            org.springframework.security.core.AuthenticationException exception) throws IOException {
        SecurityResponses.forbidden(response);
    }

    private static final class CurrentSessionLogout {

        private static final String SESSION_ID_ATTRIBUTE = CurrentSessionLogout.class.getName() + ".sessionId";

        private CurrentSessionLogout() {
        }
    }
}
