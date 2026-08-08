package dev.martin.paycore.identity.application.registration;

public enum RegistrationOperationState {
    PENDING_IDENTITY,
    IDENTITY_LINKED,
    COMPLETED,
    DUPLICATE_SUPPRESSED,
    RECONCILIATION_REQUIRED
}
