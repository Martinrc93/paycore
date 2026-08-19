package dev.martin.paycore.identity.application.authentication;

import dev.martin.paycore.identity.application.port.out.CustomerAccessRepository;
import dev.martin.paycore.identity.application.port.out.CustomerActivationPort;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import java.util.Objects;
import java.util.Optional;

public final class AuthenticateCustomerService {

    private final CustomerAccessRepository accessRepository;
    private final CustomerActivationPort activationPort;

    public AuthenticateCustomerService(CustomerAccessRepository accessRepository, CustomerActivationPort activationPort) {
        this.accessRepository = Objects.requireNonNull(accessRepository, "accessRepository");
        this.activationPort = Objects.requireNonNull(activationPort, "activationPort");
    }

    public Optional<CustomerAccess> authenticate(VerifiedCustomerLogin login) {
        Objects.requireNonNull(login, "login");
        return accessRepository.findByExternalIdentity(login.identity())
                .flatMap(access -> authenticate(access, login));
    }

    private Optional<CustomerAccess> authenticate(CustomerAccess access, VerifiedCustomerLogin login) {
        if (access.status() == CustomerStatus.ACTIVE) {
            return activationPort.confirmActive(access.customerId());
        }
        if (access.status() == CustomerStatus.PENDING_VERIFICATION && login.emailVerified()) {
            return activationPort.activatePending(access.customerId(), login.authenticatedAt());
        }
        return Optional.empty();
    }
}
