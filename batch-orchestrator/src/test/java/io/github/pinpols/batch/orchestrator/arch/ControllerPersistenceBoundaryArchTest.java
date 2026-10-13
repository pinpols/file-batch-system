package io.github.pinpols.batch.orchestrator.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 确保 HTTP 适配器通过应用用例访问数据，不直接调用持久层。 */
@DisplayName("Controller 不得直接依赖持久层")
class ControllerPersistenceBoundaryArchTest {

  @Test
  @DisplayName("Controller 不得依赖 Mapper 或 Repository")
  void controller_does_not_depend_on_persistence_mapper_or_repository() {
    var classes = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.github.pinpols.batch.orchestrator..");

    ArchRule rule = noClasses()
        .that()
        .resideInAnyPackage("..controller..", "..web..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..mapper..", "..repository..");

    rule.check(classes);
  }
}
