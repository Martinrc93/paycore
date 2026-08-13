package dev.martin.paycore.ledger.application.posting;

import dev.martin.paycore.ledger.application.port.out.LedgerAccountPort;
import dev.martin.paycore.ledger.application.port.out.LedgerTransactionStore;
import dev.martin.paycore.ledger.domain.model.FinancialTransaction;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.ledger.domain.model.LedgerAccountStatus;
import dev.martin.paycore.ledger.domain.model.LedgerEntryDirection;
import dev.martin.paycore.ledger.domain.model.LedgerLine;
import dev.martin.paycore.ledger.domain.model.LedgerValidationException;
import dev.martin.paycore.ledger.domain.model.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class PostLedgerTransactionService {

    private final LedgerAccountPort accounts;
    private final LedgerTransactionStore transactions;

    public PostLedgerTransactionService(LedgerAccountPort accounts, LedgerTransactionStore transactions) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public FinancialPostingResult post(PostLedgerTransactionCommand command) {
        Objects.requireNonNull(command, "command");
        String fingerprint = fingerprint(command);
        FinancialPostingResult existing = transactions.findIdempotent(command.idempotencyKey(), fingerprint);
        if (existing != null) {
            return existing;
        }
        List<LedgerLine> lines = command.lines().stream().map(line -> {
            LedgerAccountId accountId = new LedgerAccountId(line.accountId());
            if (accounts.findById(accountId).filter(account -> account.status() == LedgerAccountStatus.OPEN).isEmpty()) {
                throw new LedgerValidationException("Account " + line.accountId() + " is not open");
            }
            Money money = Money.of(line.amount(), line.currency());
            return new LedgerLine(line.sequence(), accountId, money, line.direction());
        }).toList();

        FinancialTransaction transaction = FinancialTransaction.confirm(
                command.postedAt(),
                command.valueDate(),
                command.idempotencyKey(),
                command.operationReference(),
                lines,
                command.reversalOf(),
                command.correctionOf());
        return transactions.post(transaction, fingerprint);
    }

    private static String fingerprint(PostLedgerTransactionCommand command) {
        String canonical = command.postedAt() + "|" + command.valueDate() + "|"
                + command.operationReference().strip() + "|" + command.reversalOf() + "|" + command.correctionOf() + "|"
                + command.lines().stream()
                .map(line -> line.sequence() + ":" + line.accountId() + ":"
                        + Money.of(line.amount(), line.currency()).amount().toPlainString()
                        + ":" + line.currency() + ":" + line.direction())
                .sorted()
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
