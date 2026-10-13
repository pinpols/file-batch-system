package io.github.pinpols.batch.orchestrator.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 确保 HTTP Controller 签名使用契约 DTO，而不是持久化 Entity。 */
@DisplayName("HTTP 契约不得泄露持久化 Entity")
class EntityApiBoundaryArchTest {

  private static final Set<String> CURRENT_ENDPOINTS = inspectEntityEndpoints();

  @Test
  @DisplayName("Orchestrator 与 Console Controller 均不得在 HTTP 契约中暴露 Entity")
  void shouldNotExposeEntitiesInHttpContracts() {
    assertThat(CURRENT_ENDPOINTS)
        .as("Controller 入参与响应使用 Request/Response DTO，不允许对已知存量设置豁免")
        .isEmpty();
  }

  private static Set<String> inspectEntityEndpoints() {
    Set<String> result = new HashSet<>();
    var classes = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(
            "io.github.pinpols.batch.orchestrator..",
            "io.github.pinpols.batch.console..",
            "io.github.pinpols.batch.common..");
    for (JavaClass owner : classes) {
      if (!owner.getSimpleName().endsWith("Controller")) {
        continue;
      }
      for (JavaMethod method : owner.getMethods()) {
        if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
          continue;
        }
        boolean entityReturn = involvesEntity(method.getReturnType());
        boolean entityParameter = method.getParameters().stream()
            .map(parameter -> parameter.getType())
            .anyMatch(EntityApiBoundaryArchTest::involvesEntity);
        if (entityReturn || entityParameter) {
          result.add(owner.getSimpleName() + "#" + method.getName());
        }
      }
    }
    return Set.copyOf(result);
  }

  private static boolean involvesEntity(JavaType type) {
    return type.getAllInvolvedRawTypes().stream()
        .anyMatch(clazz -> clazz.getPackageName().contains(".domain.")
            && clazz.getPackageName().contains(".entity")
            && clazz.getSimpleName().endsWith("Entity"));
  }
}
