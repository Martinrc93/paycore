package dev.martin.paycore.identity.application.port.out;

import dev.martin.paycore.identity.application.registration.RegistrationAcceptanceResult;
import dev.martin.paycore.identity.application.registration.RegistrationIntent;

public interface RegistrationAcceptancePort {

    RegistrationAcceptanceResult accept(RegistrationIntent intent);
}
