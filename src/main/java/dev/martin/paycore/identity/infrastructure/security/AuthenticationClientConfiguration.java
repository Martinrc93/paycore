package dev.martin.paycore.identity.infrastructure.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
        String clientAuthenticationMethod = valueOrDefault(
                registrationProperties.getClientAuthenticationMethod(), "client_secret_basic");
        if (!ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue().equals(clientAuthenticationMethod)) {
            throw new IllegalStateException("paycore must use client_secret_basic authentication");
        }
        String authorizationGrantType = required(
                registrationProperties.getAuthorizationGrantType(), "authorization grant type");
        if (!AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(authorizationGrantType)) {
            throw new IllegalStateException("paycore must use authorization_code grant");
        }
        Set<String> scopes = required(registrationProperties.getScope(), "scope");
        if (!scopes.contains("openid")) {
            throw new IllegalStateException("paycore scope must include openid");
        }

        ClientRegistration.Builder builder = ClientRegistrations.fromIssuerLocation(
                        required(provider.getIssuerUri(), "provider issuer URI"))
                .registrationId("paycore")
                .clientId(required(registrationProperties.getClientId(), "client ID"))
                .clientSecret(required(registrationProperties.getClientSecret(), "client secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(required(registrationProperties.getRedirectUri(), "redirect URI"));
        builder.scope(scopes);
        if (StringUtils.hasText(registrationProperties.getClientName())) {
            builder.clientName(registrationProperties.getClientName());
        }
        return new InMemoryClientRegistrationRepository(builder.build());
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
        requireLocalPath(successUri, "success-uri");
        requireLocalPath(logoutPath, "logout-path");
    }

    private static void requireLocalPath(String value, String property) {
        if (!isPlainLocalPath(value)) {
            throw new IllegalArgumentException(
                    "paycore.authentication." + property + " must be a local absolute path");
        }
    }

    private static boolean isPlainLocalPath(String value) {
        if (value == null || !value.startsWith("/") || value.startsWith("//")
                || value.indexOf('\\') >= 0 || value.indexOf('%') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return !uri.isAbsolute() && uri.getRawAuthority() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && value.equals(uri.getRawPath());
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
