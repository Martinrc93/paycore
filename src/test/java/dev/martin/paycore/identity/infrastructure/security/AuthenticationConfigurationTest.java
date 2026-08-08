package dev.martin.paycore.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

class AuthenticationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
            .withUserConfiguration(BoundProperties.class);

    @Test
    void authenticationStaysDisabledWithoutContactingAnIssuer() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
            assertThat(context.getEnvironment().getProperty("paycore.authentication.enabled", Boolean.class))
                    .isFalse();
        });
    }

    @Test
    void canonicalPropertyEnablesAuthenticationAndBindsOverrides() throws IOException {
        HttpServer issuerServer = startIssuerServer();
        String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();

        try {
            contextRunner.withSystemProperties(
                    "PAYCORE_OIDC_CLIENT_SECRET=overridden-client-secret",
                    "PAYCORE_AUTHENTICATION_LOGOUT_PATH=/bff/auth/local-logout")
                    .withPropertyValues(
                            "paycore.authentication.enabled=true",
                            "paycore.authentication.issuer-uri=" + issuer)
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
                        assertThat(registration.getClientId()).isEqualTo("paycore-bff");
                        assertThat(registration.getClientSecret()).isEqualTo("overridden-client-secret");
                        assertThat(registration.getAuthorizationGrantType())
                                .isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
                        assertThat(registration.getRedirectUri())
                                .isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
                        assertThat(registration.getScopes()).containsExactly("openid");
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
                            "paycore.authentication.issuer-uri=" + issuer)
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
                            "paycore.authentication.issuer-uri=" + issuer,
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
    @ComponentScan(basePackageClasses = AuthenticationConfigurationTest.class)
    static class BoundProperties {
    }
}
