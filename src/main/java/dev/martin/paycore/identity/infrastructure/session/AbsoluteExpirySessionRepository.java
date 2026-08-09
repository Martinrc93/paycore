package dev.martin.paycore.identity.infrastructure.session;

import dev.martin.paycore.identity.application.authentication.SessionLifetimePolicy;
import dev.martin.paycore.identity.infrastructure.security.CustomerOidcAuthenticationSuccessHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionOperations;

public final class AbsoluteExpirySessionRepository implements FindByIndexNameSessionRepository<Session> {

    private final JdbcIndexedSessionRepository delegate;
    private final JdbcClient jdbcClient;
    private final SessionLifetimePolicy lifetimePolicy;
    private final TransactionOperations transactions;

    public AbsoluteExpirySessionRepository(JdbcIndexedSessionRepository delegate, JdbcClient jdbcClient,
            SessionLifetimePolicy lifetimePolicy, TransactionOperations transactions) {
        this.delegate = delegate;
        this.jdbcClient = jdbcClient;
        this.lifetimePolicy = lifetimePolicy;
        this.transactions = transactions;
    }

    @Override
    public Session createSession() {
        return delegate.createSession();
    }

    @Override
    public void save(Session session) {
        transactions.executeWithoutResult(status -> {
            Instant absoluteExpiry = absoluteExpiry(session);
            if (absoluteExpiry != null) {
                session.setMaxInactiveInterval(roundUpToSeconds(
                        lifetimePolicy.remainingIdleTimeout(authenticatedAt(session))));
            }
            saveDelegate(session);
            if (absoluteExpiry != null) {
                jdbcClient.sql("""
                                UPDATE spring_session
                                SET expiry_time = LEAST(expiry_time, :absoluteExpiry)
                                WHERE session_id = :sessionId
                                """)
                        .param("absoluteExpiry", absoluteExpiry.toEpochMilli())
                        .param("sessionId", session.getId())
                        .update();
            }
        });
    }

    @Override
    public Session findById(String id) {
        Session session = delegate.findById(id);
        if (session != null && isAbsolutelyExpired(session)) {
            delegate.deleteById(id);
            return null;
        }
        return session;
    }

    @Override
    public void deleteById(String id) {
        delegate.deleteById(id);
    }

    @Override
    public Map<String, Session> findByIndexNameAndIndexValue(String indexName, String indexValue) {
        Map<String, Session> active = new LinkedHashMap<>();
        findIndexedDelegate(indexName, indexValue).forEach((id, session) -> {
            if (isAbsolutelyExpired(session)) {
                delegate.deleteById(id);
            } else {
                active.put(id, session);
            }
        });
        return active;
    }

    private boolean isAbsolutelyExpired(Session session) {
        Instant authenticatedAt = authenticatedAt(session);
        return authenticatedAt != null
                && !lifetimePolicy.remainingIdleTimeout(authenticatedAt).isPositive();
    }

    private Instant absoluteExpiry(Session session) {
        Instant authenticatedAt = authenticatedAt(session);
        return authenticatedAt == null ? null : lifetimePolicy.absoluteExpiry(authenticatedAt);
    }

    private static Instant authenticatedAt(Session session) {
        Object value = session.getAttribute(CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE);
        return value instanceof Instant authenticatedAt ? authenticatedAt : null;
    }

    private static Duration roundUpToSeconds(Duration duration) {
        long seconds = duration.getSeconds();
        if (duration.getNano() > 0) {
            seconds = Math.addExact(seconds, 1);
        }
        return Duration.ofSeconds(seconds);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void saveDelegate(Session session) {
        ((SessionRepository) delegate).save(session);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Session> findIndexedDelegate(String indexName, String indexValue) {
        return ((FindByIndexNameSessionRepository) delegate)
                .findByIndexNameAndIndexValue(indexName, indexValue);
    }
}
