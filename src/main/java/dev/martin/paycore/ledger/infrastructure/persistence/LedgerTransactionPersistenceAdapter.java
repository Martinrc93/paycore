package dev.martin.paycore.ledger.infrastructure.persistence;

import dev.martin.paycore.ledger.application.port.out.LedgerMovementQueryPort;
import dev.martin.paycore.ledger.application.port.out.LedgerTransactionStore;
import dev.martin.paycore.ledger.application.posting.FinancialPostingResult;
import dev.martin.paycore.ledger.application.posting.LedgerIdempotencyConflictException;
import dev.martin.paycore.ledger.application.query.LedgerMovement;
import dev.martin.paycore.ledger.application.query.MovementQuery;
import dev.martin.paycore.ledger.domain.model.FinancialTransaction;
import dev.martin.paycore.ledger.domain.model.LedgerLine;
import dev.martin.paycore.ledger.domain.model.Money;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LedgerTransactionPersistenceAdapter implements LedgerTransactionStore, LedgerMovementQueryPort,
        dev.martin.paycore.ledger.application.port.out.LedgerTransactionRetryPort {

    private final LedgerTransactionJpaRepository transactions;
    private final LedgerTransactionLineJpaRepository lines;
    private final LedgerPostIdempotencyJpaRepository idempotency;
    private final JdbcTemplate jdbcTemplate;

    public LedgerTransactionPersistenceAdapter(
            LedgerTransactionJpaRepository transactions,
            LedgerTransactionLineJpaRepository lines,
            LedgerPostIdempotencyJpaRepository idempotency,
            JdbcTemplate jdbcTemplate) {
        this.transactions = transactions;
        this.lines = lines;
        this.idempotency = idempotency;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public FinancialPostingResult post(FinancialTransaction transaction, String fingerprint) {
        int claimed = idempotency.claim(
                transaction.idempotencyKey(), fingerprint, transaction.postedAt());
        if (claimed == 0) {
            LedgerPostIdempotencyEntity existing = idempotency.findByKey(transaction.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("Idempotency claim disappeared"));
            if (!existing.requestFingerprint.equals(fingerprint)) {
                throw new LedgerIdempotencyConflictException();
            }
            if (existing.transactionId == null) {
                throw new IllegalStateException("Idempotency claim has no transaction result");
            }
            FinancialTransaction original = findById(existing.transactionId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency result has no transaction"));
            return new FinancialPostingResult(original, true);
        }
        for (LedgerLine line : transaction.lines()) {
            LedgerTransactionLineEntity entity = toEntity(transaction, line);
            jdbcTemplate.update("""
                    INSERT INTO ledger_transaction_lines
                        (transaction_id, line_sequence, account_id, direction, amount, currency)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, entity.id.transactionId, entity.id.sequence, entity.accountId,
                    entity.direction.name(), entity.amount, entity.currency.name());
        }
        LedgerTransactionEntity candidate = toEntity(transaction);
        jdbcTemplate.update("""
                INSERT INTO ledger_transactions
                    (id, posted_at, value_date, idempotency_key, operation_reference,
                     currency, reversal_of, correction_of)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, candidate.id, candidate.postedAt.atOffset(java.time.ZoneOffset.UTC), candidate.valueDate,
                candidate.idempotencyKey, candidate.operationReference, candidate.currency.name(),
                candidate.reversalOf, candidate.correctionOf);
        if (idempotency.complete(transaction.idempotencyKey(), transaction.id()) != 1) {
            throw new IllegalStateException("Idempotency claim could not be completed");
        }
        return new FinancialPostingResult(transaction, false);
    }

    @Override
    @Transactional
    public FinancialPostingResult findIdempotent(String idempotencyKey, String fingerprint) {
        return idempotency.findByKey(idempotencyKey)
                .map(existing -> {
                    if (!existing.requestFingerprint.equals(fingerprint)) {
                        throw new LedgerIdempotencyConflictException();
                    }
                    if (existing.transactionId == null) {
                        return null;
                    }
                    FinancialTransaction original = findById(existing.transactionId)
                            .orElseThrow(() -> new IllegalStateException("Idempotency result has no transaction"));
                    return new FinancialPostingResult(original, true);
                })
                .orElse(null);
    }

    @Override
    @Transactional
    public Optional<FinancialTransaction> findById(UUID id) {
        return transactions.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional
    public List<LedgerMovement> find(MovementQuery query) {
        return jdbcTemplate.query("""
                SELECT l.transaction_id, l.account_id, l.line_sequence, t.posted_at,
                       t.value_date, t.operation_reference, l.amount, l.currency, l.direction
                  FROM ledger_transaction_lines l
                  JOIN ledger_transactions t ON t.id = l.transaction_id
                 WHERE l.account_id = ?
                 ORDER BY t.posted_at, t.id, l.line_sequence
                 OFFSET ? LIMIT ?
                """, (resultSet, rowNum) -> new LedgerMovement(
                resultSet.getObject("transaction_id", UUID.class),
                resultSet.getObject("account_id", UUID.class),
                resultSet.getInt("line_sequence"),
                resultSet.getTimestamp("posted_at").toInstant(),
                resultSet.getObject("value_date", java.time.LocalDate.class),
                resultSet.getString("operation_reference"),
                resultSet.getBigDecimal("amount"),
                dev.martin.paycore.ledger.domain.model.CurrencyCode.of(resultSet.getString("currency")),
                dev.martin.paycore.ledger.domain.model.LedgerEntryDirection.valueOf(resultSet.getString("direction"))),
                query.accountId(), query.offset(), query.limit());
    }

    private static LedgerTransactionEntity toEntity(FinancialTransaction transaction) {
        LedgerTransactionEntity entity = new LedgerTransactionEntity();
        entity.id = transaction.id();
        entity.postedAt = transaction.postedAt();
        entity.valueDate = transaction.valueDate();
        entity.idempotencyKey = transaction.idempotencyKey();
        entity.operationReference = transaction.operationReference();
        entity.currency = transaction.currency();
        entity.reversalOf = transaction.reversalOf();
        entity.correctionOf = transaction.correctionOf();
        return entity;
    }

    private static LedgerTransactionLineEntity toEntity(FinancialTransaction transaction, LedgerLine line) {
        LedgerTransactionLineEntity entity = new LedgerTransactionLineEntity();
        entity.id = new LedgerTransactionLineId(transaction.id(), line.sequence());
        entity.accountId = line.accountId().value();
        entity.direction = line.direction();
        entity.amount = line.money().amount();
        entity.currency = line.money().currency();
        return entity;
    }

    private FinancialTransaction toDomain(LedgerTransactionEntity transaction) {
        List<LedgerLine> transactionLines = lines.findByIdTransactionIdOrderByIdSequence(transaction.id).stream()
                .map(line -> new LedgerLine(
                        line.id.sequence,
                        new dev.martin.paycore.ledger.domain.model.LedgerAccountId(line.accountId),
                        Money.of(line.amount.stripTrailingZeros(), line.currency),
                        line.direction))
                .toList();
        return new FinancialTransaction(
                transaction.id,
                transaction.postedAt,
                transaction.valueDate,
                transaction.idempotencyKey,
                transaction.operationReference,
                transactionLines,
                transaction.reversalOf,
                transaction.correctionOf);
    }
}
