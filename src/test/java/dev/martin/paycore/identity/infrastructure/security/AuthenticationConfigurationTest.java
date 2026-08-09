package dev.martin.paycore.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

class AuthenticationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BoundProperties.class);

    @Test
    void authenticationStaysDisabledWithoutContactingAnIssuer() {
        contextRunner.withUserConfiguration(AuthenticationSecurityConfiguration.class).run(context -> {
            assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
            assertThat(context).doesNotHaveBean(OAuth2AuthorizedClientRepository.class);
            assertThat(context).doesNotHaveBean(OAuth2AuthorizedClientManager.class);
            assertThat(context).doesNotHaveBean(JwtDecoderFactory.class);
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
            assertThat(context.getEnvironment().getProperty("paycore.authentication.enabled", Boolean.class))
                    .isFalse();
        });
    }

    @Test
    void canonicalPropertyEnablesAuthenticationAndBindsOverrides() throws IOException {
        HttpServer issuerServer = startIssuerServer();
        String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();

        try {
            contextRunner.withSystemProperties("PAYCORE_AUTHENTICATION_LOGOUT_PATH=/bff/auth/local-logout")
                    .withPropertyValues(
                            "paycore.authentication.enabled=true",
                            "spring.security.oauth2.client.registration.paycore.client-id=standard-client-id",
                            "spring.security.oauth2.client.registration.paycore.client-secret=standard-client-secret",
                            "spring.security.oauth2.client.registration.paycore.authorization-grant-type=authorization_code",
                            "spring.security.oauth2.client.registration.paycore.redirect-uri={baseUrl}/standard/callback/{registrationId}",
                            "spring.security.oauth2.client.registration.paycore.scope=openid,profile",
                            "spring.security.oauth2.client.provider.paycore.issuer-uri=" + issuer)
                    .run(context -> {
                        var registrations = context.getBean(ClientRegistrationRepository.class);
                        var registration = registrations.findByRegistrationId("paycore");
                        var server = context.getBean(ServerProperties.class);
                        var session = server.getServlet().getSession();
                        var cookie = session.getCookie();
                        var initializeSchema = Binder.get(context.getEnvironment())
                                .bind("spring.session.jdbc.initialize-schema", DatabaseInitializationMode.class)
                                .orElseThrow(() -> new AssertionError("JDBC session schema mode is not configured"));

                        assertThat(registration).isNotNull();
                        assertThat(registration.getRegistrationId()).isEqualTo("paycore");
                        assertThat(registration.getClientId()).isEqualTo("standard-client-id");
                        assertThat(registration.getClientSecret()).isEqualTo("standard-client-secret");
                        assertThat(registration.getAuthorizationGrantType())
                                .isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
                        assertThat(registration.getRedirectUri())
                                .isEqualTo("{baseUrl}/standard/callback/{registrationId}");
                        assertThat(registration.getScopes()).containsExactly("openid", "profile");
                        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(issuer);
                        assertThat(session.getTimeout()).isEqualTo(Duration.ofMinutes(30));
                        assertThat(initializeSchema).isEqualTo(DatabaseInitializationMode.NEVER);
                        assertThat(cookie.getName()).isEqualTo("__Host-paycore-session");
                        assertThat(cookie.getSecure()).isTrue();
                        assertThat(cookie.getHttpOnly()).isTrue();
                        assertThat(cookie.getSameSite()).isEqualTo(SameSite.LAX);
                        assertThat(cookie.getPath()).isEqualTo("/");
                        assertThat(cookie.getDomain()).isNull();
                        assertThat(context.getEnvironment()
                                .getProperty("paycore.authentication.enabled", Boolean.class)).isTrue();
                        assertThat(context.getEnvironment().getProperty("paycore.authentication.success-uri"))
                                .isEqualTo("/");
                        assertThat(context.getEnvironment().getProperty("paycore.authentication.logout-path"))
                                .isEqualTo("/bff/auth/local-logout");
                    });
        } finally {
            issuerServer.stop(0);
        }
    }

    @Test
    void authenticationCoexistsWithUnrelatedActiveProfile() throws IOException {
        HttpServer issuerServer = startIssuerServer();
        String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();

        try {
            contextRunner.withPropertyValues(
                            "spring.profiles.active=integration-test",
                            "paycore.authentication.enabled=true",
                            "spring.security.oauth2.client.registration.paycore.client-secret=test-secret",
                            "spring.security.oauth2.client.provider.paycore.issuer-uri=" + issuer)
                    .run(context -> {
                        assertThat(context).hasSingleBean(ClientRegistrationRepository.class);
                        assertThat(context.getEnvironment().getActiveProfiles())
                                .containsExactly("integration-test");
                    });
        } finally {
            issuerServer.stop(0);
        }
    }

    @Test
    void rejectsNonLocalLogoutPath() throws IOException {
        HttpServer issuerServer = startIssuerServer();
        String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();

        try {
            contextRunner.withPropertyValues(
                            "paycore.authentication.enabled=true",
                            "spring.security.oauth2.client.registration.paycore.client-secret=test-secret",
                            "spring.security.oauth2.client.provider.paycore.issuer-uri=" + issuer,
                            "paycore.authentication.logout-path=https://issuer.example/logout")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("paycore.authentication.logout-path must be a local absolute path");
                    });
        } finally {
            issuerServer.stop(0);
        }
    }

    @Test
    void rejectsNonLocalSuccessUri() throws IOException {
        HttpServer issuerServer = startIssuerServer();
        String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();

        try {
            contextRunner.withPropertyValues(
                            "paycore.authentication.enabled=true",
                            "spring.security.oauth2.client.registration.paycore.client-secret=test-secret",
                            "spring.security.oauth2.client.provider.paycore.issuer-uri=" + issuer,
                            "paycore.authentication.success-uri=//attacker.example/callback")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("paycore.authentication.success-uri must be a local absolute path");
                    });
        } finally {
            issuerServer.stop(0);
        }
    }

    @Test
    void rejectsEnabledAuthenticationWithoutAClientSecret() throws IOException {
        HttpServer issuerServer = startIssuerServer();
        String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();

        try {
            contextRunner.withPropertyValues(
                            "paycore.authentication.enabled=true",
                            "spring.security.oauth2.client.registration.paycore.client-secret=",
                            "spring.security.oauth2.client.provider.paycore.issuer-uri=" + issuer)
                    .run(context -> assertThat(context).hasFailed());
        } finally {
            issuerServer.stop(0);
        }
    }

    @Test
    void rejectsPublicClientAuthentication() throws IOException {
        HttpServer issuerServer = startIssuerServer();
        String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();

        try {
            contextRunner.withPropertyValues(
                            "paycore.authentication.enabled=true",
                            "spring.security.oauth2.client.registration.paycore.client-secret=test-secret",
                            "spring.security.oauth2.client.registration.paycore.client-authentication-method=none",
                            "spring.security.oauth2.client.provider.paycore.issuer-uri=" + issuer)
                    .run(context -> assertThat(context).hasFailed());
        } finally {
            issuerServer.stop(0);
        }
    }

    @Test
    void rejectsNonAuthorizationCodeGrant() throws IOException {
        HttpServer issuerServer = startIssuerServer();
        String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();

        try {
            contextRunner.withPropertyValues(
                            "paycore.authentication.enabled=true",
                            "spring.security.oauth2.client.registration.paycore.client-secret=test-secret",
                            "spring.security.oauth2.client.registration.paycore.authorization-grant-type=client_credentials",
                            "spring.security.oauth2.client.provider.paycore.issuer-uri=" + issuer)
                    .run(context -> assertThat(context).hasFailed());
        } finally {
            issuerServer.stop(0);
        }
    }

    @Test
    void rejectsRegistrationWithoutOpenidScope() throws IOException {
        HttpServer issuerServer = startIssuerServer();
        String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();

        try {
            contextRunner.withPropertyValues(
                            "paycore.authentication.enabled=true",
                            "spring.security.oauth2.client.registration.paycore.client-secret=test-secret",
                            "spring.security.oauth2.client.registration.paycore.scope=profile,email",
                            "spring.security.oauth2.client.provider.paycore.issuer-uri=" + issuer)
                    .run(context -> assertThat(context).hasFailed());
        } finally {
            issuerServer.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/\\attacker.example",
            "https://attacker.example/callback",
            "/%2f%2fattacker.example",
            "/%5c%5cattacker.example",
            "/signed-in?next=https://attacker.example",
            "/signed-in#https://attacker.example",
            "/signed-in\r\nLocation:https://attacker.example"
    })
    void rejectsSuccessUriThatIsNotAPlainSameSitePath(String successUri) {
        assertThatThrownBy(() -> new AuthenticationNavigationProperties(successUri, "/bff/auth/logout"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paycore.authentication.success-uri must be a local absolute path");
    }

    private static HttpServer startIssuerServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/openid-configuration", exchange -> {
            String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
            String metadata = """
                    {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",\
                    "jwks_uri":"%s/jwks","userinfo_endpoint":"%s/userinfo","subject_types_supported":["public"],\
                    "id_token_signing_alg_values_supported":["RS256"]}
                    """.formatted(issuer, issuer, issuer, issuer, issuer);
            byte[] response = metadata.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServerProperties.class)
    @ComponentScan(
            basePackageClasses = AuthenticationConfigurationTest.class,
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = AuthenticationSecurityConfiguration.class))
    static class BoundProperties {

        @Bean
        MeterRegistry authenticationTestMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
