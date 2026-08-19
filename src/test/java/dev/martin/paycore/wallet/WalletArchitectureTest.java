package dev.martin.paycore.wallet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class WalletArchitectureTest {

    private final JavaClasses walletClasses = new ClassFileImporter()
            .importPackages("dev.martin.paycore.wallet");

    @Test
    void domainDoesNotDependOnFrameworksOrOuterLayers() {
        noClasses().that().resideInAPackage("..wallet.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                        "..wallet.application..", "..wallet.infrastructure..",
                        "..ledger.infrastructure..", "..identity.infrastructure..")
                .check(walletClasses);
    }

    @Test
    void applicationDoesNotDependOnFrameworksOrInfrastructure() {
        noClasses().that().resideInAPackage("..wallet.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                        "..wallet.infrastructure..", "..ledger.infrastructure..",
                        "..identity.infrastructure..", "..ledger.application..", "..ledger.domain..")
                .check(walletClasses);
    }

    @Test
    void otherModulesDoNotDependOnWalletInfrastructure() {
        noClasses().that().resideOutsideOfPackage("..wallet..")
                .should().dependOnClassesThat().resideInAnyPackage("..wallet.infrastructure..")
                .check(new ClassFileImporter().importPackages("dev.martin.paycore"));
    }

    @Test
    void walletInfrastructureDoesNotDependOnLedgerInfrastructure() {
        noClasses().that().resideInAPackage("..wallet.infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage("..ledger.infrastructure..")
                .check(walletClasses);
    }
}
