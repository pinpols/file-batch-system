package io.github.pinpols.batch.orchestrator.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 确保 entity 包只包含持久化行模型。 */
@DisplayName("实体包仅包含 XxxEntity 持久化表行模型")
class EntityModelNamingArchTest {

  private static final String ENTITY_PACKAGE = "io.github.pinpols.batch.orchestrator.domain.entity";

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.orchestrator..");

  @Test
  @DisplayName("domain.entity 中不得残留非 Entity 模型")
  void shouldNotKeepProjectionModelsInEntityPackage() {
    Set<String> actual = CLASSES.stream()
        .filter(clazz -> ENTITY_PACKAGE.equals(clazz.getPackageName()))
        .filter(clazz -> clazz.isTopLevelClass())
        .map(clazz -> clazz.getSimpleName())
        .filter(name -> !name.endsWith("Entity"))
        .collect(Collectors.toSet());

    assertThat(actual).as("投影、查询、值对象和上下文应放在表达职责的包中").isEmpty();
  }
}
