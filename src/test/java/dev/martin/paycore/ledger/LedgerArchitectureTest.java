package dev.martin.paycore.ledger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class LedgerArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter().importPackages("dev.martin.paycore.ledger");

    @Test
    void domainDoesNotDependOnFrameworksOrOuterLayers() {
        noClasses().that().resideInAPackage("..ledger.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                        "..ledger.application..", "..ledger.infrastructure..")
                .check(classes);
    }

    @Test
    void applicationDoesNotDependOnFrameworksOrInfrastructure() {
        noClasses().that().resideInAPackage("..ledger.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                        "..ledger.infrastructure..")
                .check(classes);
    }

    @Test
    void externalModulesDoNotDependOnLedgerInfrastructure() {
        noClasses().that().resideOutsideOfPackage("..ledger..")
                .should().dependOnClassesThat().resideInAnyPackage("..ledger.infrastructure..")
                .check(new ClassFileImporter().importPackages("dev.martin.paycore"));
    }
}
