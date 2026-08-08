package dev.martin.paycore.identity.infrastructure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "paycore.authentication.enabled")
@EnableConfigurationProperties({OAuth2ClientProperties.class, AuthenticationNavigationProperties.class})
public class AuthenticationClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ClientRegistrationRepository clientRegistrationRepository(OAuth2ClientProperties properties) {
        OAuth2ClientProperties.Registration registrationProperties =
                required(properties.getRegistration().get("paycore"), "paycore client registration");
        String providerId = StringUtils.hasText(registrationProperties.getProvider())
                ? registrationProperties.getProvider() : "paycore";
        OAuth2ClientProperties.Provider provider =
                required(properties.getProvider().get(providerId), "provider " + providerId);

        ClientRegistration.Builder builder = ClientRegistrations.fromIssuerLocation(
                        required(provider.getIssuerUri(), "provider issuer URI"))
                .registrationId("paycore")
                .clientId(required(registrationProperties.getClientId(), "client ID"))
                .clientSecret(registrationProperties.getClientSecret())
                .clientAuthenticationMethod(new ClientAuthenticationMethod(valueOrDefault(
                        registrationProperties.getClientAuthenticationMethod(), "client_secret_basic")))
                .authorizationGrantType(new AuthorizationGrantType(required(
                        registrationProperties.getAuthorizationGrantType(), "authorization grant type")))
                .redirectUri(required(registrationProperties.getRedirectUri(), "redirect URI"));
        if (registrationProperties.getScope() != null) {
            builder.scope(registrationProperties.getScope());
        }
        if (StringUtils.hasText(registrationProperties.getClientName())) {
            builder.clientName(registrationProperties.getClientName());
        }
        return new InMemoryClientRegistrationRepository(builder.build());
    }

    @Bean
    @ConditionalOnMissingBean
    OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    private static <T> T required(T value, String name) {
        if (value == null || value instanceof String text && !StringUtils.hasText(text)) {
            throw new IllegalStateException("Missing " + name);
        }
        return value;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}

@ConfigurationProperties("paycore.authentication")
record AuthenticationNavigationProperties(String successUri, String logoutPath) {

    AuthenticationNavigationProperties {
        if (logoutPath == null || !logoutPath.startsWith("/") || logoutPath.startsWith("//")) {
            throw new IllegalArgumentException("paycore.authentication.logout-path must be a local absolute path");
        }
    }
}
