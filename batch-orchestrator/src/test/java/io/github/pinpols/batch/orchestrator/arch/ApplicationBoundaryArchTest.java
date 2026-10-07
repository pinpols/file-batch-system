package io.github.pinpols.batch.orchestrator.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Guards application services from depending on HTTP controller DTOs. */
@DisplayName("架构守护:任务应用服务不得依赖控制器层及其请求与响应数据对象")
class ApplicationBoundaryArchTest {

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.orchestrator..");

  @Test
  @DisplayName("位于任务应用服务包内的类不出现对控制器层请求与响应类型的直接依赖")
  void shouldNotDependOnControllerLayer_whenInTaskApplicationServicePackage() {
    noClasses()
        .that()
        .resideInAPackage("..application.service.task..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..controller..", "..controller.request..", "..controller.response..")
        .check(CLASSES);
  }
}
