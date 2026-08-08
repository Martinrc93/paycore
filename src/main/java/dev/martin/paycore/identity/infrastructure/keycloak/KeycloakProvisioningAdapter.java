package dev.martin.paycore.identity.infrastructure.keycloak;

import dev.martin.paycore.identity.application.port.out.ExternalIdentityProvisioner;
import dev.martin.paycore.identity.application.registration.ProvisionedIdentity;
import dev.martin.paycore.identity.application.registration.ProvisioningException;
import dev.martin.paycore.identity.application.registration.ProvisioningFailure;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.Email;
import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

public class KeycloakProvisioningAdapter implements ExternalIdentityProvisioner {

    private static final String OWNERSHIP_ATTRIBUTE = "paycore_customer_id";
    private final RestClient restClient;
    private final KeycloakProvisioningProperties properties;
    private final Clock clock;

    public KeycloakProvisioningAdapter(
            RestClient.Builder builder, KeycloakProvisioningProperties properties, Clock clock) {
        this.restClient = Objects.requireNonNull(builder, "builder").build();
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ProvisionedIdentity provision(CustomerId customerId, Email email) {
        String accessToken = serviceToken();
        Map<String, Object> request = Map.of(
                "username", email.value(),
                "email", email.value(),
                "enabled", true,
                "emailVerified", false,
                "requiredActions", List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"),
                "attributes", Map.of(OWNERSHIP_ATTRIBUTE, List.of(customerId.value().toString())));
        try {
            URI location = restClient.post()
                    .uri(properties.baseUrl() + "/admin/realms/{realm}/users", properties.realm())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
            if (location == null) {
                throw reconciliation("KEYCLOAK_CREATE_LOCATION_MISSING", null);
            }
            return new ProvisionedIdentity(properties.issuer(), lastPathSegment(location));
        } catch (HttpClientErrorException.Conflict conflict) {
            return recoverOwnedIdentity(accessToken, customerId, email, false, conflict);
        } catch (ResourceAccessException ambiguousCreate) {
            return recoverOwnedIdentity(accessToken, customerId, email, true, ambiguousCreate);
        } catch (RuntimeException exception) {
            throw classify("KEYCLOAK_CREATE", exception);
        }
    }

    @Override
    public void sendRequiredActions(String subject) {
        try {
            restClient.put()
                    .uri(uriBuilder -> uriBuilder
                            .scheme(URI.create(properties.baseUrl()).getScheme())
                            .host(URI.create(properties.baseUrl()).getHost())
                            .port(URI.create(properties.baseUrl()).getPort())
                            .path("/admin/realms/{realm}/users/{subject}/execute-actions-email")
                            .queryParam("client_id", properties.clientId())
                            .queryParam("redirect_uri", properties.redirectUri())
                            .queryParam("lifespan", properties.actionLifespan().toSeconds())
                            .build(properties.realm(), subject))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException exception) {
            throw classify("KEYCLOAK_ACTION_EMAIL", exception);
        }
    }

    private ProvisionedIdentity recoverOwnedIdentity(String accessToken, CustomerId customerId, Email email,
            boolean missingIsRetryable, Throwable createFailure) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> users = restClient.get()
                    .uri(properties.baseUrl()
                                    + "/admin/realms/{realm}/users?username={username}&exact=true&briefRepresentation=false",
                            properties.realm(), email.value())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(List.class);
            if (users == null || users.isEmpty()) {
                if (missingIsRetryable) {
                    throw retryable("KEYCLOAK_CREATE_AMBIGUOUS", createFailure);
                }
                throw reconciliation("KEYCLOAK_OWNED_USER_NOT_FOUND", null);
            }
            if (users.size() != 1 || !hasOwnership(users.getFirst(), customerId)
                    || users.getFirst().get("id") == null) {
                throw reconciliation("KEYCLOAK_OWNERSHIP_CONFLICT", null);
            }
            return new ProvisionedIdentity(properties.issuer(), users.getFirst().get("id").toString());
        } catch (ProvisioningException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw classify("KEYCLOAK_RECOVERY", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasOwnership(Map<String, Object> user, CustomerId customerId) {
        Object rawAttributes = user.get("attributes");
        if (!(rawAttributes instanceof Map<?, ?> attributes)) {
            return false;
        }
        Object rawValues = attributes.get(OWNERSHIP_ATTRIBUTE);
        if (rawValues instanceof List<?> values) {
            return values.size() == 1 && customerId.value().toString().equals(values.getFirst());
        }
        return customerId.value().toString().equals(rawValues);
    }

    private String serviceToken() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(properties.baseUrl() + "/realms/{realm}/protocol/openid-connect/token",
                            properties.realm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            Object token = response == null ? null : response.get("access_token");
            if (token == null) {
                throw reconciliation("KEYCLOAK_SERVICE_TOKEN_MISSING", null);
            }
            return token.toString();
        } catch (ProvisioningException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw classify("KEYCLOAK_SERVICE_TOKEN", exception);
        }
    }

    private ProvisioningException classify(String operation, RuntimeException exception) {
        if (exception instanceof ProvisioningException provisioningException) {
            return provisioningException;
        }
        if (exception instanceof ResourceAccessException) {
            return retryable(operation + "_IO", exception);
        }
        if (exception instanceof HttpStatusCodeException statusException) {
            int status = statusException.getStatusCode().value();
            if (status == 429 || status >= 500) {
                return new ProvisioningException(ProvisioningFailure.RETRYABLE, operation + "_" + status,
                        status == 429 ? retryAfter(statusException) : null, exception);
            }
            return reconciliation(operation + "_" + status, exception);
        }
        return reconciliation(operation + "_UNEXPECTED", exception);
    }

    private static ProvisioningException retryable(String code, Throwable cause) {
        return new ProvisioningException(ProvisioningFailure.RETRYABLE, code, cause);
    }

    private static ProvisioningException reconciliation(String code, Throwable cause) {
        return new ProvisioningException(ProvisioningFailure.RECONCILIATION_REQUIRED, code, cause);
    }

    private Duration retryAfter(HttpStatusCodeException exception) {
        String value = exception.getResponseHeaders() == null
                ? null : exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException ignored) {
            try {
                Instant requestedAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(clock.instant(), requestedAt);
                return delay.isPositive() ? delay : null;
            } catch (RuntimeException invalidDate) {
                return null;
            }
        }
    }

    private static String lastPathSegment(URI location) {
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
