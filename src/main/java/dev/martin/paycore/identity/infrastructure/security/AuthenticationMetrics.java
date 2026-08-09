package dev.martin.paycore.identity.infrastructure.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public final class AuthenticationMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationMetrics.class);

    private final MeterRegistry meters;

    public AuthenticationMetrics(ObjectProvider<MeterRegistry> meters) {
        this.meters = meters.getIfAvailable(SimpleMeterRegistry::new);
    }

    public void loginFailure() {
        meters.counter("paycore.authentication.login.failures", "reason", "authentication_rejected").increment();
        LOG.info("Authentication event category=login_failure reason=authentication_rejected");
    }

    public void refreshFailure() {
        meters.counter("paycore.authentication.refresh.failures", "reason", "refresh_rejected").increment();
        LOG.info("Authentication event category=refresh_failure reason=refresh_rejected");
    }

    public void customerAccessDenied() {
        meters.counter("paycore.authentication.customer.access.denials", "reason", "customer_unavailable")
                .increment();
        LOG.info("Authentication event category=customer_access_denial reason=customer_unavailable");
    }

    public void currentSessionRevoked(int count) {
        sessionRevoked("current", count);
    }

    public void allSessionsRevoked(int count) {
        sessionRevoked("all", count);
    }

    public void expiredSessionsCleaned(int count) {
        meters.counter("paycore.authentication.session.cleanup.runs", "reason", "scheduled").increment();
        meters.counter("paycore.authentication.sessions.expired", "reason", "expired").increment(count);
        LOG.info("Authentication event category=session_cleanup reason=expired");
    }

    private void sessionRevoked(String scope, int count) {
        meters.counter("paycore.authentication.session.revocations", "scope", scope).increment();
        meters.counter("paycore.authentication.sessions.revoked", "scope", scope).increment(count);
        LOG.info("Authentication event category=session_revocation reason=requested");
    }
}
