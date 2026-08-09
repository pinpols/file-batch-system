package io.github.pinpols.batch.console.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * P1 层边界守护：冻结 console-api 的 web 层依赖方向。
 *
 * <p>console-api 历史结构是「bounded context 内嵌分层 + 平铺遗留包」混排（详见
 * docs/architecture/project-structure.md）。本测试只冻结当前已为 0 违规的方向，
 * 防止新代码重新引入；存量违规不在本测试内硬性清零，逐步迁移。
 *
 * <p>已知存量违规（本测试显式放行，待清理）：
 *
 * <ul>
 *   <li>domain.&lt;ctx&gt;.infrastructure → domain.&lt;ctx&gt;.web 约 10 处（应用服务直接引用 web
 *       DTO，属"存量慢慢挪"范围，暂不冻结）
 * </ul>
 */
class LayerBoundaryArchTest {

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.console..");

  @Test
  void webMustNotDependOnMapper() {
    noClasses()
        .that()
        .resideInAPackage("..web..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..mapper..")
        .because("web 层禁止直连 mapper，数据访问统一经 application/infrastructure")
        .check(CLASSES);
  }

  @Test
  void webMustNotDependOnInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("..web..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..infrastructure..")
        .because("web 层禁止直接依赖基础设施实现（仅允许经 application 接口）")
        .check(CLASSES);
  }
}
