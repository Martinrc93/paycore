package dev.martin.paycore.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.testsupport.ProtectedSecurityTestConfiguration;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.web.servlet.function.RequestPredicates.POST;
import static org.springframework.web.servlet.function.RouterFunctions.route;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "paycore.authentication.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false"
})
@Import({ProtectedSecurityTestConfiguration.class, BrowserSecurityTest.StateChangeConfiguration.class})
class BrowserSecurityTest {

    private static final String SESSION_COOKIE = "__Host-paycore-session";
    private static final UUID CUSTOMER_ID = UUID.fromString("70000000-0000-0000-0000-000000000007");
    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("70000000-0000-0000-0000-000000000008");
    private static final Instant NOW = ProtectedSessionSecurityTest.NOW;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC")
            .withEnv("PGTZ", "UTC");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcIndexedSessionRepository sessions;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    OAuth2AuthorizedClientRepository authorizedClients;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    StateChanges stateChanges;

    @BeforeEach
    void resetState() {
        jdbcClient.sql("TRUNCATE TABLE spring_session CASCADE").update();
        jdbcClient.sql("TRUNCATE TABLE external_identities, customers CASCADE").update();
        stateChanges.reset();
    }

    @Test
    void publicAllowlistIsExplicitByMethodPathAndDispatcherType() throws Exception {
        assertThat(mockMvc.perform(get("/actuator/health").secure(true)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mockMvc.perform(post("/api/customers")
                        .secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getStatus()).isNotEqualTo(401);
        assertThat(mockMvc.perform(get("/bff/auth/csrf").secure(true)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mockMvc.perform(get("/oauth2/authorization/paycore").secure(true)).andReturn()
                .getResponse().getStatus()).isEqualTo(302);
        assertThat(mockMvc.perform(get("/login/oauth2/code/paycore")
                        .secure(true)
                        .queryParam("error", "access_denied"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/error")
                        .secure(true)
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            request.setAttribute("jakarta.servlet.error.status_code", 404);
                            return request;
                        }))
                .andReturn().getResponse().getStatus()).isNotEqualTo(401);
        assertUnauthorized(get("/bff/auth/session").with(request -> {
            request.setDispatcherType(DispatcherType.ERROR);
            return request;
        }));

        assertUnauthorized(get("/api/customers"));
        assertUnauthorized(post("/bff/auth/csrf"));
        assertUnauthorized(get("/actuator/health/details"));
        assertUnauthorized(get("/error"));
        assertUnauthorized(get("/bff/auth/not-public"));
        assertUnauthorized(get("/test/protected"));
    }

    @Test
    void csrfBootstrapExposesOnlySessionBoundTokenAndRequestNamesWithoutReadableCookie() throws Exception {
        MvcResult first = mockMvc.perform(get("/bff/auth/csrf").secure(true)).andReturn();
        Map<String, String> payload = csrfPayload(first);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(payload).containsOnlyKeys("token", "headerName", "parameterName");
        assertThat(payload.get("token")).isNotBlank();
        assertThat(payload.get("headerName")).isEqualTo("X-CSRF-TOKEN");
        assertThat(payload.get("parameterName")).isEqualTo("_csrf");
        assertThat(first.getResponse().getCookies()).allSatisfy(cookie -> {
            assertThat(cookie.getName()).isEqualTo(SESSION_COOKIE);
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getValue()).doesNotContain(payload.get("token"));
        });

        Cookie cookie = requiredCookie(first);
        Map<String, String> sameSession = csrfPayload(mockMvc.perform(get("/bff/auth/csrf")
                        .secure(true).cookie(cookie)).andReturn());
        Map<String, String> otherSession = csrfPayload(mockMvc.perform(get("/bff/auth/csrf")
                        .secure(true)).andReturn());
        assertThat(sameSession.get("token")).isEqualTo(payload.get("token"));
        assertThat(otherSession.get("token")).isNotEqualTo(payload.get("token"));
    }

    @Test
    void validCsrfAllowsUnsafeProcessingWhileMissingInvalidAndCrossSessionTokensDoNothing() throws Exception {
        Session first = authenticatedSession(CUSTOMER_ID, true);
        Session second = authenticatedSession(OTHER_CUSTOMER_ID, true);
        Csrf firstCsrf = csrfFor(first);
        Csrf secondCsrf = csrfFor(second);

        MvcResult missing = mockMvc.perform(post("/test/state-change")
                        .secure(true).cookie(sessionCookie(first)))
                .andReturn();
        MvcResult invalid = mockMvc.perform(post("/test/state-change")
                        .secure(true).cookie(sessionCookie(first))
                        .header(firstCsrf.headerName(), "invalid"))
                .andReturn();
        MvcResult crossSession = mockMvc.perform(post("/test/state-change")
                        .secure(true).cookie(sessionCookie(first))
                        .header(firstCsrf.headerName(), secondCsrf.token()))
                .andReturn();

        assertForbidden(missing);
        assertForbidden(invalid);
        assertForbidden(crossSession);
        assertThat(stateChanges.count()).isZero();
        assertThat(sessions.findById(first.getId())).isNotNull();
        assertThat(sessions.findById(second.getId())).isNotNull();

        MvcResult valid = mockMvc.perform(post("/test/state-change")
                        .secure(true).cookie(sessionCookie(first))
                        .header(firstCsrf.headerName(), firstCsrf.token()))
                .andReturn();
        assertThat(valid.getResponse().getStatus()).isEqualTo(204);
        assertThat(stateChanges.count()).isEqualTo(1);
    }

    @Test
    void logoutDeletesOnlyCurrentPostgresSessionAndTokensExpiresExactCookieAndCannotResurrect() throws Exception {
        Session current = authenticatedSession(CUSTOMER_ID, true);
        Session other = authenticatedSession(CUSTOMER_ID, true);
        Csrf csrf = csrfFor(current);
        assertThat(sessionAttributeCount(current.getId())).isPositive();
        assertThat(sessionAttributeCount(other.getId())).isPositive();

        MvcResult result = mockMvc.perform(post("/bff/auth/logout")
                        .secure(true)
                        .cookie(sessionCookie(current))
                        .header(csrf.headerName(), csrf.token()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getHeader("Location")).isNull();
        Cookie expired = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(expired).isNotNull();
        assertThat(expired.getMaxAge()).isZero();
        assertThat(expired.getPath()).isEqualTo("/");
        assertThat(expired.getDomain()).isNull();
        assertThat(expired.getSecure()).isTrue();
        assertThat(expired.isHttpOnly()).isTrue();
        assertThat(sessions.findById(current.getId())).isNull();
        assertThat(sessionAttributeCount(current.getId())).isZero();
        assertThat(sessions.findById(other.getId())).isNotNull();
        assertThat(sessionAttributeCount(other.getId())).isPositive();

        assertThat(mockMvc.perform(get("/bff/auth/session").secure(true).cookie(sessionCookie(other)))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        assertUnauthorized(get("/bff/auth/session").cookie(sessionCookie(current)));
        assertUnauthorized(post("/bff/auth/logout").cookie(sessionCookie(current)));
        assertThat(sessions.findById(current.getId())).isNull();
    }

    @Test
    void logoutRequiresCurrentSessionsCsrfAndFailuresPreserveSessionAndAuthorizedClient() throws Exception {
        Session current = authenticatedSession(CUSTOMER_ID, true);
        Session other = authenticatedSession(CUSTOMER_ID, true);
        Csrf currentCsrf = csrfFor(current);
        Csrf otherCsrf = csrfFor(other);

        assertForbidden(mockMvc.perform(post("/bff/auth/logout")
                        .secure(true).cookie(sessionCookie(current))).andReturn());
        assertForbidden(mockMvc.perform(post("/bff/auth/logout")
                        .secure(true).cookie(sessionCookie(current))
                        .header(currentCsrf.headerName(), "invalid")).andReturn());
        assertForbidden(mockMvc.perform(post("/bff/auth/logout")
                        .secure(true).cookie(sessionCookie(current))
                        .header(currentCsrf.headerName(), otherCsrf.token())).andReturn());

        assertThat(sessions.findById(current.getId())).isNotNull();
        assertThat(sessions.findById(other.getId())).isNotNull();
        assertThat(hasAuthorizedClient(sessions.findById(current.getId()))).isTrue();
    }

    @Test
    void bootstrapOnlySessionCannotTriggerLogoutHandlersWithoutLocalAuthentication() throws Exception {
        MvcResult bootstrap = mockMvc.perform(get("/bff/auth/csrf").secure(true)).andReturn();
        Cookie cookie = requiredCookie(bootstrap);
        String sessionId = repositorySessionId(cookie);
        Session session = sessions.findById(sessionId);
        assertThat(session).isNotNull();
        session.setAttribute("bootstrap-marker", "preserved");
        sessionRepository().save(session);
        long attributesBefore = sessionAttributeCount(sessionId);

        MvcResult result = mockMvc.perform(post("/bff/auth/logout")
                        .secure(true)
                        .cookie(cookie))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"code\":\"unauthorized\"}");
        assertThat(result.getResponse().getCookie(SESSION_COOKIE)).isNull();
        Session preserved = sessions.findById(sessionId);
        assertThat(preserved).isNotNull();
        assertThat(preserved.<String>getAttribute("bootstrap-marker")).isEqualTo("preserved");
        assertThat(sessionAttributeCount(sessionId)).isEqualTo(attributesBefore);
    }

    private void assertUnauthorized(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        MvcResult result = mockMvc.perform(request.secure(true)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"code\":\"unauthorized\"}");
    }

    private static void assertForbidden(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"code\":\"forbidden\"}");
    }

    private Csrf csrfFor(Session session) throws Exception {
        MvcResult result = mockMvc.perform(get("/bff/auth/csrf").secure(true).cookie(sessionCookie(session)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, String> payload = csrfPayload(result);
        assertThat(payload).containsKeys("token", "headerName");
        return new Csrf(payload.get("token"), payload.get("headerName"));
    }

    private Map<String, String> csrfPayload(MvcResult result) throws Exception {
        Matcher matcher = Pattern.compile("\\\"([^\\\"]+)\\\":\\\"([^\\\"]*)\\\"")
                .matcher(result.getResponse().getContentAsString());
        Map<String, String> values = new LinkedHashMap<>();
        while (matcher.find()) {
            values.put(matcher.group(1), matcher.group(2));
        }
        return values;
    }

    private Session authenticatedSession(UUID customerId, boolean withAuthorizedClient) {
        insertCustomer(customerId);
        Session session = sessionRepository().createSession();
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                new CustomerPrincipal(customerId), List.of(), "paycore");
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, customerId.toString());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        session.setAttribute(CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE, NOW);
        if (withAuthorizedClient) {
            copyAuthorizedClientAttribute(session, authentication);
        }
        sessionRepository().save(session);
        return session;
    }

    private void copyAuthorizedClientAttribute(Session session, OAuth2AuthenticationToken authentication) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession source = new MockHttpSession();
        request.setSession(source);
        authorizedClients.saveAuthorizedClient(authorizedClient(authentication.getName()), authentication,
                request, new MockHttpServletResponse());
        source.getAttributeNames().asIterator().forEachRemaining(name ->
                session.setAttribute(name, source.getAttribute(name)));
    }

    private OAuth2AuthorizedClient authorizedClient(String principalName) {
        Instant issuedAt = NOW.minusSeconds(60);
        return new OAuth2AuthorizedClient(registrations.findByRegistrationId("paycore"), principalName,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "browser-access-token",
                        issuedAt, NOW.plusSeconds(300)),
                new OAuth2RefreshToken("browser-refresh-token", issuedAt));
    }

    private void insertCustomer(UUID customerId) {
        jdbcClient.sql("""
                        INSERT INTO customers (id, email, customer_type, status, created_at, updated_at)
                        VALUES (:id, :email, 'INDIVIDUAL', :status, :now, :now)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", customerId)
                .param("email", customerId + "@example.test")
                .param("status", CustomerStatus.ACTIVE.name())
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();
    }

    private long sessionAttributeCount(String sessionId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM spring_session_attributes attributes
                        JOIN spring_session session ON session.primary_id = attributes.session_primary_id
                        WHERE session.session_id = :sessionId
                        """)
                .param("sessionId", sessionId)
                .query(Long.class).single();
    }

    private static boolean hasAuthorizedClient(Session session) {
        return session.getAttributeNames().stream()
                .map(session::<Object>getAttribute)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(clients -> clients.containsKey("paycore"));
    }

    private static Cookie requiredCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private static Cookie sessionCookie(Session session) {
        return new Cookie(SESSION_COOKIE, Base64.getEncoder().encodeToString(
                session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String repositorySessionId(Cookie cookie) {
        return new String(Base64.getDecoder().decode(cookie.getValue()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessionRepository() {
        return (SessionRepository<Session>) (SessionRepository<?>) sessions;
    }

    private record Csrf(String token, String headerName) {
    }

    static final class StateChanges {

        private final AtomicInteger count = new AtomicInteger();

        void apply() {
            count.incrementAndGet();
        }

        int count() {
            return count.get();
        }

        void reset() {
            count.set(0);
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class StateChangeConfiguration {

        @Bean
        StateChanges stateChanges() {
            return new StateChanges();
        }

        @Bean
        RouterFunction<ServerResponse> stateChangeRoute(StateChanges stateChanges) {
            return route(POST("/test/state-change"), request -> {
                stateChanges.apply();
                return ServerResponse.noContent().build();
            });
        }
    }
}
