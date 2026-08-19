package dev.martin.paycore.wallet.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.infrastructure.security.CustomerOidcAuthenticationSuccessHandler;
import dev.martin.paycore.identity.infrastructure.security.CustomerPrincipal;
import dev.martin.paycore.testsupport.ProtectedSecurityTestConfiguration;
import dev.martin.paycore.wallet.application.provisioning.ProvisionWalletCommand;
import dev.martin.paycore.wallet.application.provisioning.ProvisionWalletService;
import dev.martin.paycore.wallet.domain.model.Wallet;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.context.annotation.Import;
import org.springframework.session.SessionRepository;
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
class WalletControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final UUID OWNER = UUID.fromString("70000000-0000-0000-0000-000000000007");
    private static final UUID CALLER = UUID.fromString("80000000-0000-0000-0000-000000000008");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC").withEnv("PGTZ", "UTC");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    JdbcIndexedSessionRepository sessions;

    @Autowired
    ProvisionWalletService provisioning;

    @BeforeEach
    void resetState() {
        jdbcClient.sql("TRUNCATE TABLE spring_session, wallets, ledger_account_balances, ledger_accounts, "
                + "external_identities, customers CASCADE").update();
    }

    @Test
    void rejectsUnauthenticatedWalletQueryWithSanitized401() throws Exception {
        mockMvc.perform(get("/api/wallet").secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"unauthorized\"}"));
    }

    @Test
    void rejectsInactiveCustomerWithSanitized403() throws Exception {
        insertCustomer(CALLER, CustomerStatus.SUSPENDED);
        Session session = authenticatedSession(CALLER);

        mockMvc.perform(get("/api/wallet").secure(true).cookie(sessionCookie(session)))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"forbidden\"}"));
    }

    @Test
    void returnsOwnZeroBalanceWithoutLedgerAccountIdsAndWithoutCsrf() throws Exception {
        insertCustomer(OWNER, CustomerStatus.ACTIVE);
        Wallet wallet = provisioning.provision(new ProvisionWalletCommand(OWNER, WalletCurrency.USD));
        Session session = authenticatedSession(OWNER);

        MvcResult result = mockMvc.perform(get("/api/wallet").secure(true).cookie(sessionCookie(session)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"walletId":"%s","status":"UNFUNDED","currency":"USD",
                         "available":0,"reserved":0,"total":0}
                        """.formatted(wallet.id().value())))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(wallet.availableAccountId().toString())
                .doesNotContain(wallet.reservedAccountId().toString());
    }

    @Test
    void doesNotAcceptAnotherCustomerIdAndDoesNotRevealAnotherWallet() throws Exception {
        insertCustomer(OWNER, CustomerStatus.ACTIVE);
        Wallet wallet = provisioning.provision(new ProvisionWalletCommand(OWNER, WalletCurrency.USD));
        insertCustomer(CALLER, CustomerStatus.ACTIVE);
        Wallet callerWallet = provisioning.provision(new ProvisionWalletCommand(CALLER, WalletCurrency.USD));
        Session session = authenticatedSession(CALLER);

        MvcResult result = mockMvc.perform(get("/api/wallet")
                        .secure(true)
                        .queryParam("customerId", OWNER.toString())
                        .cookie(sessionCookie(session)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"walletId":"%s","status":"UNFUNDED","currency":"USD",
                         "available":0,"reserved":0,"total":0}
                        """.formatted(callerWallet.id().value())))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(wallet.id().value().toString());
    }

    @Test
    void rejectsInconsistentWalletBeforeControllerWithSanitizedForbidden() throws Exception {
        insertCustomer(OWNER, CustomerStatus.ACTIVE);
        Wallet wallet = provisioning.provision(new ProvisionWalletCommand(OWNER, WalletCurrency.USD));
        jdbcClient.sql("UPDATE ledger_account_balances SET consistency_status = 'INCONSISTENT' "
                + "WHERE account_id = :accountId").param("accountId", wallet.availableAccountId()).update();
        Session session = authenticatedSession(OWNER);

        MvcResult result = mockMvc.perform(get("/api/wallet").secure(true).cookie(sessionCookie(session)))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"forbidden\"}"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("inconsistent")
                .doesNotContain(wallet.availableAccountId().toString());
    }

    private void insertCustomer(UUID customerId, CustomerStatus status) {
        jdbcClient.sql("""
                INSERT INTO customers (id, email, customer_type, status, created_at, updated_at, version)
                VALUES (:id, :email, 'INDIVIDUAL', :status, :at, :at, 0)
                """)
                .param("id", customerId)
                .param("email", customerId + "@example.test")
                .param("status", status.name())
                .param("at", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();
    }

    private Session authenticatedSession(UUID customerId) {
        Session session = sessionRepository().createSession();
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                new CustomerPrincipal(customerId), List.of(), "paycore");
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, customerId.toString());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        session.setAttribute(CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE, NOW);
        sessionRepository().save(session);
        return session;
    }

    private Cookie sessionCookie(Session session) {
        return new Cookie("__Host-paycore-session",
                Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessionRepository() {
        return (SessionRepository<Session>) (SessionRepository<?>) sessions;
    }
}
