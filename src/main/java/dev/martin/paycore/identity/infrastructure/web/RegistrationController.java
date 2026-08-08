package dev.martin.paycore.identity.infrastructure.web;

import dev.martin.paycore.identity.application.registration.RegisterCustomerCommand;
import dev.martin.paycore.identity.application.registration.RegisterCustomerService;
import dev.martin.paycore.identity.application.registration.RegistrationResponse;
import dev.martin.paycore.identity.domain.model.Email;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
@ConditionalOnProperty(name = "paycore.registration.enabled", havingValue = "true")
public class RegistrationController {

    private final RegisterCustomerService service;
    private final RegistrationRateLimiter rateLimiter;

    public RegistrationController(RegisterCustomerService service, RegistrationRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RegistrationResponse register(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RegistrationRequest request,
            HttpServletRequest servletRequest) {
        Email email = Email.of(request.email());
        rateLimiter.check(servletRequest.getRemoteAddr(), email);
        return service.register(new RegisterCustomerCommand(
                idempotencyKey, email.value(), request.customerType()));
    }
}
