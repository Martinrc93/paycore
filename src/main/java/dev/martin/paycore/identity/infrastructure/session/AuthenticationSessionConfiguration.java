package dev.martin.paycore.identity.infrastructure.session;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

@Configuration(proxyBeanMethods = false)
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 30 * 60)
public class AuthenticationSessionConfiguration {
}
