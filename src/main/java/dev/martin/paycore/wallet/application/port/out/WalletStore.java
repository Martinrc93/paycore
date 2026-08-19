package dev.martin.paycore.wallet.application.port.out;

import dev.martin.paycore.wallet.domain.model.Wallet;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface WalletStore {

    <T> T inTransaction(Supplier<T> work);

    Optional<Wallet> lockAndFindByCustomerId(UUID customerId);

    Wallet claim(Wallet wallet);

    Optional<Wallet> findByCustomerId(UUID customerId);

    Wallet save(Wallet wallet);
}
