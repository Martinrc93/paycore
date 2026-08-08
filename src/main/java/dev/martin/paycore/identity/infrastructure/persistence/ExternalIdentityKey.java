package dev.martin.paycore.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
class ExternalIdentityKey implements Serializable {

    @Column(length = 512)
    String issuer;

    @Column(length = 255)
    String subject;

    protected ExternalIdentityKey() {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExternalIdentityKey key
                && Objects.equals(issuer, key.issuer)
                && Objects.equals(subject, key.subject);
    }

    @Override
    public int hashCode() {
        return Objects.hash(issuer, subject);
    }
}
