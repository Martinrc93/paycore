package dev.martin.paycore.identity.infrastructure.config;

import dev.martin.paycore.identity.application.port.out.ExternalIdentityProvisioner;
import dev.martin.paycore.identity.application.port.out.RegistrationAcceptancePort;
import dev.martin.paycore.identity.application.port.out.RegistrationWorkPort;
import dev.martin.paycore.identity.application.port.out.RegistrationAlertPort;
import dev.martin.paycore.identity.application.registration.IdempotencyDigester;
import dev.martin.paycore.identity.application.registration.ProcessRegistrationService;
import dev.martin.paycore.identity.application.registration.RegisterCustomerService;
import dev.martin.paycore.identity.application.registration.RegistrationBackoff;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.infrastructure.keycloak.KeycloakProvisioningAdapter;
import dev.martin.paycore.identity.infrastructure.keycloak.KeycloakProvisioningProperties;
import dev.martin.paycore.identity.infrastructure.web.RegistrationRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class RegistrationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "paycore.registration.enabled", havingValue = "true")
    IdempotencyDigester registrationIdempotencyDigester(
            @Value("${paycore.registration.idempotency-current-version}") int currentVersion,
            @Value("${paycore.registration.idempotency-secrets}") String configuredSecrets) {
        return new IdempotencyDigester(currentVersion, parseDigestSecrets(configuredSecrets));
    }

    static Map<Integer, byte[]> parseDigestSecrets(String configuredSecrets) {
        if (configuredSecrets == null || configuredSecrets.isBlank()) {
            throw new IllegalArgumentException("At least one idempotency digest secret is required");
        }
        Map<Integer, byte[]> secrets = new LinkedHashMap<>();
        for (String entry : configuredSecrets.split(",")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Digest secrets must use version=base64 format");
            }
            int version;
            byte[] secret;
            try {
                version = Integer.parseInt(parts[0]);
                secret = Base64.getDecoder().decode(parts[1]);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Digest secrets must use version=base64 format", exception);
            }
            if (secrets.putIfAbsent(version, secret) != null) {
                throw new IllegalArgumentException("Digest secret versions must be unique");
            }
        }
        return secrets;
    }

    @Bean
    @ConditionalOnProperty(name = "paycore.registration.enabled", havingValue = "true")
    RegisterCustomerService registerCustomerService(RegistrationAcceptancePort acceptancePort,
            IdempotencyDigester digester, Clock clock) {
        return new RegisterCustomerService(acceptancePort, digester, clock, CustomerId::newId);
    }

    @Bean
    @ConditionalOnProperty(name = "paycore.registration.enabled", havingValue = "true")
    RegistrationRateLimiter registrationRateLimiter(JdbcClient jdbcClient, Clock clock,
            @Value("${paycore.registration.rate-limit-secret}") String secret,
            @Value("${paycore.registration.rate-limit.source:20}") int sourceLimit,
            @Value("${paycore.registration.rate-limit.email:5}") int emailLimit) {
        return new RegistrationRateLimiter(jdbcClient, secret, clock, sourceLimit, emailLimit);
    }

    @Bean
    @ConditionalOnProperty(name = "paycore.registration.worker-enabled", havingValue = "true")
    ExternalIdentityProvisioner externalIdentityProvisioner(RestClient.Builder builder, Clock clock,
            @Value("${paycore.registration.keycloak.base-url}") String baseUrl,
            @Value("${paycore.registration.keycloak.realm}") String realm,
            @Value("${paycore.registration.keycloak.issuer}") String issuer,
            @Value("${paycore.registration.keycloak.client-id}") String clientId,
            @Value("${paycore.registration.keycloak.client-secret}") String clientSecret,
            @Value("${paycore.registration.keycloak.redirect-uri}") String redirectUri,
            @Value("${paycore.registration.keycloak.action-lifespan:1h}") Duration actionLifespan,
            @Value("${paycore.registration.keycloak.connect-timeout:5s}") Duration connectTimeout,
            @Value("${paycore.registration.keycloak.read-timeout:30s}") Duration readTimeout,
            @Value("${paycore.registration.worker.lease-duration:2m}") Duration leaseDuration) {
        if (connectTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.toMillis() < 1
                || connectTimeout.toMillis() > Integer.MAX_VALUE
                || readTimeout.isNegative() || readTimeout.isZero() || readTimeout.toMillis() < 1
                || readTimeout.toMillis() > Integer.MAX_VALUE
                || connectTimeout.plus(readTimeout).multipliedBy(3).compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "Three sequential Keycloak request timeouts must fit within the worker lease");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return new KeycloakProvisioningAdapter(builder.requestFactory(requestFactory), new KeycloakProvisioningProperties(
                baseUrl, realm, issuer, clientId, clientSecret, redirectUri, actionLifespan), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "paycore.registration.worker-enabled", havingValue = "true")
    ProcessRegistrationService processRegistrationService(RegistrationWorkPort workPort,
            ExternalIdentityProvisioner provisioner, RegistrationAlertPort alertPort, Clock clock,
            @Value("${paycore.registration.worker.lease-duration:2m}") Duration leaseDuration,
            @Value("${paycore.registration.worker.alert-threshold:5}") int alertThreshold) {
        return new ProcessRegistrationService(workPort, provisioner,
                new RegistrationBackoff(Duration.ofSeconds(5), Duration.ofHours(1)),
                alertPort, alertThreshold, clock, leaseDuration);
    }
}
