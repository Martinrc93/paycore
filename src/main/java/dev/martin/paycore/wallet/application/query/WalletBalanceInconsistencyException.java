package dev.martin.paycore.wallet.application.query;

public class WalletBalanceInconsistencyException extends IllegalStateException {

    public WalletBalanceInconsistencyException(String message) {
        super(message);
    }

    public WalletBalanceInconsistencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
