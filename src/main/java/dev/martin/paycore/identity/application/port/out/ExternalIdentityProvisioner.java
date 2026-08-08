package dev.martin.paycore.identity.application.port.out;

import dev.martin.paycore.identity.application.registration.ProvisionedIdentity;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.Email;

public interface ExternalIdentityProvisioner {

    ProvisionedIdentity provision(CustomerId customerId, Email email);

    void sendRequiredActions(String subject);
}
