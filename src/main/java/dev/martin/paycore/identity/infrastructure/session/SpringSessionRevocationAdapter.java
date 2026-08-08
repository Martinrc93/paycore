package dev.martin.paycore.identity.infrastructure.session;

import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Component;

@Component
public class SpringSessionRevocationAdapter implements SessionRevocationPort {

    private final JdbcIndexedSessionRepository repository;

    public SpringSessionRevocationAdapter(JdbcIndexedSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void revokeCurrent(String sessionId) {
        repository.deleteById(sessionId);
    }

    @Override
    public void revokeAll(CustomerId customerId) {
        repository.findByPrincipalName(customerId.value().toString()).keySet().forEach(repository::deleteById);
    }
}
