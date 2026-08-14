package dev.martin.paycore.identity.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.martin.paycore.identity.application.authentication.ChangeCustomerStatusService;
import dev.martin.paycore.identity.application.port.out.CustomerRepository;
import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.infrastructure.security.CustomerPrincipal;
import dev.martin.paycore.identity.infrastructure.security.ProtectedSessionSecurityTest;
import dev.martin.paycore.testsupport.ProtectedSecurityTestConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "paycore.authentication.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false"
})
@Import(ProtectedSecurityTestConfiguration.class)
class ConcurrentStatusRevocationTest {

    private static final Instant NOW = ProtectedSessionSecurityTest.NOW;
    private static final UUID CUSTOMER_ID = UUID.fromString("70000000-0000-0000-0000-000000000007");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC")
            .withEnv("PGTZ", "UTC");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    JdbcIndexedSessionRepository sessions;

    @Autowired
    FindByIndexNameSessionRepository<Session> customerSessions;

    @Autowired
    ChangeCustomerStatusService statusChanges;

    @Autowired
    CustomerRepository customers;

    @Autowired
    SpringSessionRevocationAdapter sessionRevocations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ProtectedSessionSecurityTest.MutableClock clock;

    @Autowired
    ProtectedSessionSecurityTest.RecordingAuthorizedClientManager authorizedClientManager;

    @BeforeEach
    void resetState() {
        jdbcClient.sql("TRUNCATE TABLE spring_session CASCADE").update();
        jdbcClient.sql("TRUNCATE TABLE external_identities, customers CASCADE").update();
        clock.set(NOW);
        authorizedClientManager.reset();
    }

    @Test
    void suspensionRacesWithAnInFlightRequestButNoRequestStartingAfterCommitCanSucceed() throws Exception {
        insertActiveCustomer();
        Session first = authenticatedSession();
        Session second = authenticatedSession();
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(10)) {
            Future<MvcResult> racingRequest = executor.submit(() -> performAfter(start, cookie(first)));
            Future<?> suspension = executor.submit(() -> {
                await(start);
                statusChanges.suspend(new CustomerId(CUSTOMER_ID));
            });
            start.countDown();
            racingRequest.get();
            suspension.get();

            assertThat(customerStatus()).isEqualTo(CustomerStatus.SUSPENDED);
            assertThat(indexedSessions().findByPrincipalName(CUSTOMER_ID.toString())).isEmpty();

            List<Future<Integer>> postCommitRequests = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                Cookie staleCookie = index % 2 == 0 ? cookie(first) : cookie(second);
                postCommitRequests.add(executor.submit(() -> perform(staleCookie).getResponse().getStatus()));
            }
            for (Future<Integer> response : postCommitRequests) {
                assertThat(response.get()).isEqualTo(401);
            }
        }
    }

    @Test
    void blockingCommitsTheStatusAndProactivelyRevokesEveryPostgreSqlSession() {
        insertActiveCustomer();
        Session first = authenticatedSession();
        Session second = authenticatedSession();

        statusChanges.block(new CustomerId(CUSTOMER_ID));

        assertThat(customerStatus()).isEqualTo(CustomerStatus.BLOCKED);
        assertThat(sessions.findById(first.getId())).isNull();
        assertThat(sessions.findById(second.getId())).isNull();
    }

    @Test
    void statusAndSessionRevocationRollBackTogetherWhenTheTransitionCannotCommit() {
        insertActiveCustomer();
        Session session = authenticatedSession();
        SessionRevocationPort failingRevocation = new SessionRevocationPort() {
            @Override
            public void revokeCurrent(String sessionId) {
                throw new AssertionError("Current-session revocation was not expected");
            }

            @Override
            public void revokeAll(CustomerId customerId) {
                sessionRevocations.revokeAll(customerId);
                throw new IllegalStateException("revocation failed");
            }
        };
        ChangeCustomerStatusService service = new ChangeCustomerStatusService(
                customers, failingRevocation, Clock.fixed(NOW, ZoneOffset.UTC));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                ignored -> service.suspend(new CustomerId(CUSTOMER_ID))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("revocation failed");

        assertThat(customerStatus()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(sessions.findById(session.getId())).isNotNull();
    }

    @Test
    void statusUpdateCommitsBeforeRevocationSoAConcurrentSessionSaveCannotReappear() throws Exception {
        insertActiveCustomer();
        Session pendingSession = unsavedAuthenticatedSession();
        CountDownLatch revocationStarted = new CountDownLatch(1);
        CountDownLatch releaseRevocation = new CountDownLatch(1);
        SessionRevocationPort controlledRevocation = new SessionRevocationPort() {
            @Override
            public void revokeCurrent(String sessionId) {
                throw new AssertionError("Current-session revocation was not expected");
            }

            @Override
            public void revokeAll(CustomerId customerId) {
                revocationStarted.countDown();
                await(releaseRevocation);
            }
        };
        ChangeCustomerStatusService service = new ChangeCustomerStatusService(
                customers, controlledRevocation, Clock.fixed(NOW, ZoneOffset.UTC));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            Future<?> statusChange = executor.submit(() -> transaction.executeWithoutResult(
                    ignored -> service.block(new CustomerId(CUSTOMER_ID))));
            revocationStarted.await();
            Future<?> sessionSave = executor.submit(() -> guardedSessionRepository().save(pendingSession));
            releaseRevocation.countDown();
            statusChange.get();
            sessionSave.get();
        }

        assertThat(customerStatus()).isEqualTo(CustomerStatus.BLOCKED);
        assertThat(sessions.findById(pendingSession.getId())).isNull();
    }

    private Session authenticatedSession() {
        Session session = guardedSessionRepository().createSession();
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                new CustomerPrincipal(CUSTOMER_ID), List.of(), "paycore");
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, CUSTOMER_ID.toString());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        session.setAttribute("paycore.authenticated-at", NOW.minus(Duration.ofHours(1)));
        guardedSessionRepository().save(session);
        return session;
    }

    private Session unsavedAuthenticatedSession() {
        Session session = guardedSessionRepository().createSession();
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                new CustomerPrincipal(CUSTOMER_ID), List.of(), "paycore");
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, CUSTOMER_ID.toString());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        session.setAttribute("paycore.authenticated-at", NOW.minus(Duration.ofHours(1)));
        return session;
    }

    private void insertActiveCustomer() {
        jdbcClient.sql("""
                        INSERT INTO customers (id, email, customer_type, status, created_at, updated_at)
                        VALUES (:id, :email, 'INDIVIDUAL', 'ACTIVE', :now, :now)
                        """)
                .param("id", CUSTOMER_ID)
                .param("email", CUSTOMER_ID + "@example.test")
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();
    }

    private CustomerStatus customerStatus() {
        String status = jdbcClient.sql("SELECT status FROM customers WHERE id = :id")
                .param("id", CUSTOMER_ID)
                .query(String.class)
                .single();
        return CustomerStatus.valueOf(status);
    }

    private MvcResult performAfter(CountDownLatch start, Cookie cookie) {
        await(start);
        try {
            return perform(cookie);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MvcResult perform(Cookie cookie) throws Exception {
        return mockMvc.perform(get("/test/protected").secure(true).cookie(cookie)).andReturn();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static Cookie cookie(Session session) {
        return new Cookie(ProtectedSessionSecurityTest.SESSION_COOKIE,
                Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessionRepository() {
        return (SessionRepository<Session>) (SessionRepository<?>) sessions;
    }

    @SuppressWarnings("unchecked")
    private FindByIndexNameSessionRepository<Session> guardedSessionRepository() {
        return (FindByIndexNameSessionRepository<Session>) customerSessions;
    }

    @SuppressWarnings("unchecked")
    private FindByIndexNameSessionRepository<Session> indexedSessions() {
        return (FindByIndexNameSessionRepository<Session>) (FindByIndexNameSessionRepository<?>) sessions;
    }
}
