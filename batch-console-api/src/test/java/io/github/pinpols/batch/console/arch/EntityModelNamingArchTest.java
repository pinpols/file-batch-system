package io.github.pinpols.batch.console.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Console Entity 包只包含 XxxEntity 持久化模型")
class EntityModelNamingArchTest {

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.console..");

  @Test
  @DisplayName("各领域 entity 包不得存放查询投影或其他非表模型")
  void shouldKeepNonEntityModelsOutsideEntityPackages_whenScanningConsole() {
    Set<String> nonEntityModels = CLASSES.stream()
        .filter(clazz -> clazz.isTopLevelClass())
        .filter(clazz -> clazz.getPackageName().contains(".domain."))
        .filter(clazz -> clazz.getPackageName().endsWith(".entity"))
        .map(clazz -> clazz.getName())
        .filter(name -> !name.substring(name.lastIndexOf('.') + 1).endsWith("Entity"))
        .collect(Collectors.toSet());

    assertThat(nonEntityModels)
        .as("Mapper 行、聚合与查询模型分别放入 query/view，entity 包只包含持久化模型")
        .isEmpty();
  }
}
