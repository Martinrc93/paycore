package dev.martin.paycore.identity.infrastructure.security;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "paycore.authentication.enabled")
@EnableConfigurationProperties(AuthenticationClientProperties.class)
public class AuthenticationClientConfiguration {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(AuthenticationClientProperties properties) {
        ClientRegistration registration = ClientRegistrations.fromIssuerLocation(properties.issuerUri().toString())
                .registrationId("paycore")
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.redirectUri())
                .scope("openid")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }
}

@ConfigurationProperties("paycore.authentication")
record AuthenticationClientProperties(
        URI issuerUri,
        String clientId,
        String clientSecret,
        String redirectUri,
        String successUri,
        String logoutPath) {

    AuthenticationClientProperties {
        if (logoutPath == null || !logoutPath.startsWith("/") || logoutPath.startsWith("//")) {
            throw new IllegalArgumentException("paycore.authentication.logout-path must be a local absolute path");
        }
    }
}
