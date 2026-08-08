package dev.martin.paycore.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RegistrationOperationJpaRepository extends JpaRepository<RegistrationOperationEntity, UUID> {

    @Modifying
    @Query("""
            delete from RegistrationOperationEntity operation
            where operation.expiresAt <= :now
              and operation.state in (
                dev.martin.paycore.identity.application.registration.RegistrationOperationState.COMPLETED,
                dev.martin.paycore.identity.application.registration.RegistrationOperationState.DUPLICATE_SUPPRESSED
              )
            """)
    int deleteExpiredTerminal(Instant now);
}
