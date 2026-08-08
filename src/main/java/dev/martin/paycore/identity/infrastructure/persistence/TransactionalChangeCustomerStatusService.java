package dev.martin.paycore.identity.infrastructure.persistence;

import dev.martin.paycore.identity.application.authentication.ChangeCustomerStatusService;
import dev.martin.paycore.identity.application.port.out.CustomerRepository;
import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class TransactionalChangeCustomerStatusService extends ChangeCustomerStatusService {

    TransactionalChangeCustomerStatusService(CustomerRepository customers,
            SessionRevocationPort sessions, Clock clock) {
        super(customers, sessions, clock);
    }

    @Override
    @Transactional
    public void suspend(CustomerId customerId) {
        super.suspend(customerId);
    }

    @Override
    @Transactional
    public void block(CustomerId customerId) {
        super.block(customerId);
    }
}
