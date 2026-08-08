package dev.martin.paycore.identity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class IdentityArchitectureTest {

    private final com.tngtech.archunit.core.domain.JavaClasses classes =
            new ClassFileImporter().importPackages("dev.martin.paycore.identity");

    @Test
    void domainDoesNotDependOnFrameworksOrOuterLayers() {
        noClasses().that().resideInAPackage("..identity.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                        "org.keycloak..", "..identity.application..", "..identity.infrastructure..")
                .check(classes);
    }

    @Test
    void applicationDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage("..identity.application..")
                .should().dependOnClassesThat().resideInAPackage("..identity.infrastructure..")
                .check(classes);
    }
}
