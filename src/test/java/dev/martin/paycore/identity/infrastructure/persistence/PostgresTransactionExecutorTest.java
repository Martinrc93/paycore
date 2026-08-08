package dev.martin.paycore.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionCallback;

class PostgresTransactionExecutorTest {

    private final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };

    @Test
    void retriesDeadlockAndSerializationInFreshBoundedTransactions() {
        assertRetriesSqlState("40P01");
        assertRetriesSqlState("40001");
    }

    @Test
    void doesNotRetryOtherDatabaseFailures() {
        AtomicInteger attempts = new AtomicInteger();
        PostgresTransactionExecutor executor = new PostgresTransactionExecutor(transactions, 3);

        assertThatThrownBy(() -> executor.execute(() -> {
            attempts.incrementAndGet();
            throw failure("23505");
        })).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(attempts).hasValue(1);
    }

    private void assertRetriesSqlState(String sqlState) {
        AtomicInteger attempts = new AtomicInteger();
        PostgresTransactionExecutor executor = new PostgresTransactionExecutor(transactions, 3);

        String result = executor.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw failure(sqlState);
            }
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(attempts).hasValue(3);
    }

    private static DataAccessResourceFailureException failure(String sqlState) {
        return new DataAccessResourceFailureException("database failure", new SQLException("failure", sqlState));
    }
}
