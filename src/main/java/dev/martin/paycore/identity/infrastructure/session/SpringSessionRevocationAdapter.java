package dev.martin.paycore.identity.infrastructure.session;

import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.infrastructure.security.AuthenticationMetrics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class SpringSessionRevocationAdapter implements SessionRevocationPort {

    private final JdbcClient jdbcClient;
    private final AuthenticationMetrics metrics;

    public SpringSessionRevocationAdapter(JdbcClient jdbcClient, AuthenticationMetrics metrics) {
        this.jdbcClient = jdbcClient;
        this.metrics = metrics;
    }

    @Override
    public void revokeCurrent(String sessionId) {
        int count = jdbcClient.sql("DELETE FROM spring_session WHERE session_id = :sessionId")
                .param("sessionId", sessionId)
                .update();
        metrics.currentSessionRevoked(count);
    }

    @Override
    public void revokeAll(CustomerId customerId) {
        int count = jdbcClient.sql("DELETE FROM spring_session WHERE principal_name = :principalName")
                .param("principalName", customerId.value().toString())
                .update();
        metrics.allSessionsRevoked(count);
    }
}
