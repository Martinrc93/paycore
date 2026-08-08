package dev.martin.paycore.identity.application.registration;

import dev.martin.paycore.identity.application.port.out.RegistrationAcceptancePort;
import dev.martin.paycore.identity.domain.model.Customer;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.Email;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

public final class RegisterCustomerService {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private final RegistrationAcceptancePort acceptancePort;
    private final IdempotencyDigester digester;
    private final Clock clock;
    private final Supplier<CustomerId> customerIdFactory;

    public RegisterCustomerService(RegistrationAcceptancePort acceptancePort, IdempotencyDigester digester,
            Clock clock, Supplier<CustomerId> customerIdFactory) {
        this.acceptancePort = Objects.requireNonNull(acceptancePort, "acceptancePort");
        this.digester = Objects.requireNonNull(digester, "digester");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.customerIdFactory = Objects.requireNonNull(customerIdFactory, "customerIdFactory");
    }

    public RegistrationResponse register(RegisterCustomerCommand command) {
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        Email email = Email.of(command.email());
        IdempotencyDigests keyDigests = digester.digest(command.idempotencyKey());
        Customer customer = Customer.register(customerIdFactory.get(), email, command.type(), now);
        RegistrationIntent intent = new RegistrationIntent(
                keyDigests,
                digester.fingerprint(email, command.type()),
                customer,
                now.plus(IDEMPOTENCY_RETENTION));

        if (acceptancePort.accept(intent) == RegistrationAcceptanceResult.CONFLICT) {
            throw new IdempotencyConflictException();
        }
        return RegistrationResponse.accepted();
    }
}
