package dev.martin.paycore.identity.domain.model;

public enum CustomerStatus {
    PROVISIONING,
    PENDING_VERIFICATION,
    ACTIVE,
    PROVISIONING_FAILED,
    SUSPENDED,
    BLOCKED
}
