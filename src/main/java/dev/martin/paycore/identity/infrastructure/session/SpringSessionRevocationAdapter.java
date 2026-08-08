package dev.martin.paycore.identity.infrastructure.session;

import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class SpringSessionRevocationAdapter implements SessionRevocationPort {

    private final JdbcClient jdbcClient;

    public SpringSessionRevocationAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void revokeCurrent(String sessionId) {
        jdbcClient.sql("DELETE FROM spring_session WHERE session_id = :sessionId")
                .param("sessionId", sessionId)
                .update();
    }

    @Override
    public void revokeAll(CustomerId customerId) {
        jdbcClient.sql("DELETE FROM spring_session WHERE principal_name = :principalName")
                .param("principalName", customerId.value().toString())
                .update();
    }
}
