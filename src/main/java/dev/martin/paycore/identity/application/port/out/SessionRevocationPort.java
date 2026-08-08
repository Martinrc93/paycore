package dev.martin.paycore.identity.application.port.out;

import dev.martin.paycore.identity.domain.model.CustomerId;

public interface SessionRevocationPort {

    void revokeCurrent(String sessionId);

    void revokeAll(CustomerId customerId);
}
