package dev.martin.paycore.ledger.infrastructure.persistence;

import dev.martin.paycore.ledger.application.port.out.LedgerAccountPort;
import dev.martin.paycore.ledger.application.port.out.LedgerAccountStore;
import dev.martin.paycore.ledger.domain.model.LedgerAccount;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import java.util.Optional;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LedgerAccountPersistenceAdapter implements LedgerAccountStore {

    private final LedgerAccountJpaRepository accounts;
    private final Clock clock;

    public LedgerAccountPersistenceAdapter(LedgerAccountJpaRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override
    public Optional<LedgerAccount> findById(LedgerAccountId id) {
        return accounts.findById(id.value()).map(LedgerAccountPersistenceAdapter::toDomain);
    }

    @Override
    public LedgerAccount save(LedgerAccount account) {
        return toDomain(accounts.save(toEntity(account)));
    }

    private static LedgerAccount toDomain(LedgerAccountEntity entity) {
        return new LedgerAccount(
                new LedgerAccountId(entity.id), entity.type, entity.status, entity.name);
    }

    private LedgerAccountEntity toEntity(LedgerAccount account) {
        LedgerAccountEntity entity = new LedgerAccountEntity();
        entity.id = account.id().value();
        entity.type = account.type();
        entity.status = account.status();
        entity.name = account.name();
        entity.createdAt = clock.instant();
        return entity;
    }
}
