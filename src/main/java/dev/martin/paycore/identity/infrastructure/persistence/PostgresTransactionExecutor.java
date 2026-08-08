package dev.martin.paycore.identity.infrastructure.persistence;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class PostgresTransactionExecutor {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private final TransactionOperations transactions;
    private final int maxAttempts;

    @Autowired
    PostgresTransactionExecutor(PlatformTransactionManager transactionManager) {
        this(new TransactionTemplate(transactionManager), DEFAULT_MAX_ATTEMPTS);
    }

    PostgresTransactionExecutor(TransactionOperations transactions, int maxAttempts) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    <T> T execute(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> work.get());
            } catch (DataAccessException exception) {
                if (attempt >= maxAttempts || !isRetryable(exception)) {
                    throw exception;
                }
            }
        }
    }

    private static boolean isRetryable(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && ("40P01".equals(sqlException.getSQLState())
                    || "40001".equals(sqlException.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
