package io.github.pinpols.batch.orchestrator.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Guards application services from depending on HTTP controller DTOs. */
class ApplicationBoundaryArchTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.pinpols.batch.orchestrator..");

  @Test
  void taskApplicationServicesDoNotDependOnControllerLayer() {
    noClasses()
        .that()
        .resideInAPackage("..application.service.task..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..controller..", "..controller.request..", "..controller.response..")
        .check(CLASSES);
  }
}
