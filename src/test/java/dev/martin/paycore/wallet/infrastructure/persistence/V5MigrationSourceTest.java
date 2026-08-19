package dev.martin.paycore.wallet.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V5MigrationSourceTest {

    @Test
    void doesNotDeclareADatabaseRebuildFunction() throws IOException {
        try (InputStream migration = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V5__create_wallet_accounts_and_balances.sql")) {
            assertThat(migration).isNotNull();
            String source = new String(migration.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(source).doesNotContain("rebuild_ledger_account_balance");
        }
    }

    @Test
    void requiresAnActivationInstantForActiveWallets() throws IOException {
        try (InputStream migration = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V5__create_wallet_accounts_and_balances.sql")) {
            assertThat(migration).isNotNull();
            String source = new String(migration.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(source).contains("status <> 'ACTIVE' OR activated_at IS NOT NULL");
        }
    }

    @Test
    void requiresBothBalanceProjectionsAndProtectsNaturalNonNegativeBalances() throws IOException {
        try (InputStream migration = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V5__create_wallet_accounts_and_balances.sql")) {
            assertThat(migration).isNotNull();
            String source = new String(migration.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(source).contains("JOIN ledger_account_balances available_balance");
            assertThat(source).contains("JOIN ledger_account_balances reserved_balance");
            assertThat(source).contains("validate_ledger_account_balance_natural");
            assertThat(source).contains("ledger_account_balance_natural");
            assertThat(source).contains("validate_ledger_account_policy_natural");
        }
    }

    @Test
    void validatesWalletLinkedAccountStatusAgainstTheWalletLifecycle() throws IOException {
        try (InputStream migration = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V5__create_wallet_accounts_and_balances.sql")) {
            assertThat(migration).isNotNull();
            String source = new String(migration.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(source).contains("expected_account_status := CASE");
            assertThat(source).contains("WHEN wallet_status IN ('UNFUNDED', 'ACTIVE') THEN 'OPEN'");
            assertThat(source).contains("WHEN wallet_status = 'BLOCKED' THEN 'BLOCKED'");
            assertThat(source).contains("WHEN wallet_status = 'CLOSED' THEN 'CLOSED'");
        }
    }

    @Test
    void requiresConsistentProjectionsForEveryOperationalWalletValidation() throws IOException {
        try (InputStream migration = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V5__create_wallet_accounts_and_balances.sql")) {
            assertThat(migration).isNotNull();
            String source = new String(migration.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(source).contains("OR b.consistency_status = 'CONSISTENT'");
            assertThat(count(source, "AND available_balance.consistency_status = 'CONSISTENT'")).isEqualTo(2);
            assertThat(count(source, "AND reserved_balance.consistency_status = 'CONSISTENT'")).isEqualTo(2);
        }
    }

    @Test
    void defersWalletAccountLifecycleValidationUntilCommitForBothTables() throws IOException {
        try (InputStream migration = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V5__create_wallet_accounts_and_balances.sql")) {
            assertThat(migration).isNotNull();
            String source = new String(migration.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(source).contains("CREATE CONSTRAINT TRIGGER trg_wallet_account_lifecycle_wallet");
            assertThat(source).contains("CREATE CONSTRAINT TRIGGER trg_wallet_account_lifecycle_account");
            assertThat(source).contains("DEFERRABLE INITIALLY DEFERRED");
            assertThat(source).contains("validate_wallet_account_lifecycle");
            assertThat(source).contains("AFTER UPDATE OF status ON ledger_accounts");
        }
    }

    private static int count(String source, String fragment) {
        int occurrences = 0;
        int offset = 0;
        while ((offset = source.indexOf(fragment, offset)) >= 0) {
            occurrences++;
            offset += fragment.length();
        }
        return occurrences;
    }
}
