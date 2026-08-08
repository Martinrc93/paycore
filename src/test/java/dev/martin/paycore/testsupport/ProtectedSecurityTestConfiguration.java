package dev.martin.paycore.testsupport;

import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RouterFunctions.route;

import dev.martin.paycore.identity.infrastructure.security.ProtectedSessionSecurityTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@TestConfiguration(proxyBeanMethods = false)
public class ProtectedSecurityTestConfiguration {

    @Bean
    @Primary
    ProtectedSessionSecurityTest.MutableClock taskClock() {
        return new ProtectedSessionSecurityTest.MutableClock(ProtectedSessionSecurityTest.NOW);
    }

    @Bean
    ClientRegistrationRepository clientRegistrationRepository() {
        return new InMemoryClientRegistrationRepository(clientRegistration());
    }

    @Bean
    @Primary
    ProtectedSessionSecurityTest.RecordingAuthorizedClientManager taskAuthorizedClientManager() {
        return new ProtectedSessionSecurityTest.RecordingAuthorizedClientManager(clientRegistration());
    }

    @Bean
    RouterFunction<ServerResponse> protectedRoute() {
        return route(GET("/test/protected"), request -> ServerResponse.ok()
                .body(request.principal().orElseThrow().getName()));
    }

    private static ClientRegistration clientRegistration() {
        return ClientRegistration.withRegistrationId("paycore")
                .clientId("paycore-test")
                .clientSecret("test-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri("https://idp.example.test/authorize")
                .tokenUri("https://idp.example.test/token")
                .jwkSetUri("https://idp.example.test/jwks")
                .issuerUri("https://idp.example.test")
                .userNameAttributeName("sub")
                .build();
    }
}
