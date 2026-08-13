package dev.martin.paycore.ledger.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerTransactionJpaRepository extends JpaRepository<LedgerTransactionEntity, UUID> {
}
