package dev.martin.paycore.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface ExternalIdentityJpaRepository extends JpaRepository<ExternalIdentityEntity, ExternalIdentityKey> {
}
