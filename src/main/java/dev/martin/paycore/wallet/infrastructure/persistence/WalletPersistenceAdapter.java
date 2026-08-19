package dev.martin.paycore.wallet.infrastructure.persistence;

import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.domain.model.Wallet;
import dev.martin.paycore.wallet.domain.model.WalletId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

@Component
public class WalletPersistenceAdapter implements WalletStore {

    private final WalletJpaRepository wallets;
    private final JdbcClient jdbcClient;
    private final TransactionOperations transactions;

    public WalletPersistenceAdapter(WalletJpaRepository wallets, JdbcClient jdbcClient,
            TransactionOperations transactions) {
        this.wallets = Objects.requireNonNull(wallets, "wallets");
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return transactions.execute(status -> work.get());
    }

    @Override
    public Optional<Wallet> lockAndFindByCustomerId(UUID customerId) {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:customerId AS text), 0))")
                .param("customerId", customerId)
                .query()
                .singleRow();
        return findByCustomerId(customerId);
    }

    @Override
    public Wallet claim(Wallet wallet) {
        wallets.claim(
                wallet.id().value(), wallet.customerId(), wallet.currency().name(),
                wallet.availableAccountId(), wallet.reservedAccountId(), wallet.status().name(),
                wallet.createdAt(), wallet.updatedAt());
        return findByCustomerId(wallet.customerId())
                .orElseThrow(() -> new IllegalStateException("Wallet claim disappeared"));
    }

    @Override
    public Optional<Wallet> findByCustomerId(UUID customerId) {
        return wallets.findByCustomerId(customerId).map(WalletPersistenceAdapter::toDomain);
    }

    @Override
    public Wallet save(Wallet wallet) {
        WalletEntity entity = wallets.findById(wallet.id().value()).orElse(null);
        if (entity == null) {
            if (wallet.version() != 0) {
                throw new OptimisticLockingFailureException("Wallet does not exist at version " + wallet.version());
            }
            return toDomain(wallets.saveAndFlush(toEntity(wallet)));
        }

        long expectedVersion = entity.version + 1;
        if (wallet.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Wallet version mismatch: expected " + expectedVersion + " but was " + wallet.version());
        }

        entity.customerId = wallet.customerId();
        entity.currency = wallet.currency();
        entity.availableAccountId = wallet.availableAccountId();
        entity.reservedAccountId = wallet.reservedAccountId();
        entity.status = wallet.status();
        entity.preBlockStatus = wallet.preBlockStatus();
        entity.activatedAt = wallet.activatedAt();
        entity.createdAt = wallet.createdAt();
        entity.updatedAt = wallet.updatedAt();
        wallets.flush();
        return toDomain(entity);
    }

    private static Wallet toDomain(WalletEntity entity) {
        return new Wallet(
                new WalletId(entity.id), entity.customerId, entity.currency,
                entity.availableAccountId, entity.reservedAccountId, entity.status,
                entity.preBlockStatus, entity.activatedAt, entity.createdAt, entity.updatedAt,
                entity.version);
    }

    private static WalletEntity toEntity(Wallet wallet) {
        WalletEntity entity = new WalletEntity();
        entity.id = wallet.id().value();
        entity.customerId = wallet.customerId();
        entity.currency = wallet.currency();
        entity.availableAccountId = wallet.availableAccountId();
        entity.reservedAccountId = wallet.reservedAccountId();
        entity.status = wallet.status();
        entity.preBlockStatus = wallet.preBlockStatus();
        entity.activatedAt = wallet.activatedAt();
        entity.createdAt = wallet.createdAt();
        entity.updatedAt = wallet.updatedAt();
        entity.version = wallet.version();
        return entity;
    }

}
