package dev.martin.paycore.identity.application.registration;

public record RegistrationResponse(String message) {

    private static final RegistrationResponse ACCEPTED =
            new RegistrationResponse("If registration can proceed, check your email.");

    public static RegistrationResponse accepted() {
        return ACCEPTED;
    }
}
