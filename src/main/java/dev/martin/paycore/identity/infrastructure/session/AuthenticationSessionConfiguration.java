package dev.martin.paycore.identity.infrastructure.session;

import dev.martin.paycore.identity.application.authentication.SessionLifetimePolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 30 * 60)
public class AuthenticationSessionConfiguration {

    @Bean("springSessionTransactionOperations")
    TransactionOperations springSessionTransactionOperations(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @Primary
    @ConditionalOnBooleanProperty(name = "paycore.authentication.enabled")
    FindByIndexNameSessionRepository<Session> absoluteExpirySessionRepository(
            JdbcIndexedSessionRepository delegate,
            JdbcClient jdbcClient,
            SessionLifetimePolicy lifetimePolicy,
            TransactionOperations springSessionTransactionOperations) {
        return new AbsoluteExpirySessionRepository(
                delegate, jdbcClient, lifetimePolicy, springSessionTransactionOperations);
    }
}
