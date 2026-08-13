package dev.martin.paycore.ledger.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerTransactionLineJpaRepository extends JpaRepository<LedgerTransactionLineEntity, LedgerTransactionLineId> {

    List<LedgerTransactionLineEntity> findByIdTransactionIdOrderByIdSequence(UUID transactionId);
}
