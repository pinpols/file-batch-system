package io.github.pinpols.batch.e2e.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.common.arch.CodingConventionsArchRules;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;

/**
 * Spring 装配守护 — 防 P0/P1-B 已踩过的 2 类 main-red:
 *
 * <ol>
 *   <li>PR #111 fix(main-red): {@code BatchTaskExecutorRegistry} 双 public ctor 无 @Autowired → SB4
 *       回退 no-arg → NoSuchMethodException 启动失败
 *   <li>PR #113 / #114 fix(e2e): 新加 {@code common.spi.task} / {@code common.resilience} 子包, 但
 *       E2eConsoleImportApplication 等 e2e app 的 {@code @ComponentScan basePackages} 没扫到 → bean 找不到
 * </ol>
 *
 * <p>放 batch-e2e-tests 里因为它依赖了所有 worker / orchestrator / console / trigger / common, 扫一次就能查覆盖所有 SB
 * Application 类(含 main app + e2e 测试 app)。
 *
 * <p>规则定义在 {@code batch-common.test-jar} 里的 {@link CodingConventionsArchRules},本类只做 wiring + 触发。
 */
class SpringWiringGuardArchTest {

  /** 项目所有生产 + 测试 class(含 e2e app)。 */
  private static final JavaClasses ALL_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("io.github.pinpols.batch");

  @Test
  void componentScanCoversSharedSpiAndResiliencePackages() {
    CodingConventionsArchRules.componentScanCoversRule(
            "io.github.pinpols.batch.common.spi.task", "io.github.pinpols.batch.common.resilience")
        .check(ALL_CLASSES);
  }

  @Test
  void multipleCtorsRequireAutowired() {
    CodingConventionsArchRules.multipleCtorsRequireAutowiredRule().check(ALL_CLASSES);
  }

  /**
   * 第 3 类 main-red(2026-07-09,PR #770):console-api 新增 {@code console.shared} 子包放跨域共享 bean,但 {@link
   * io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication} 的 ComponentScan 是显式子包白名单,没扫到 →
   * e2e 上下文 NoSuchBean 启动失败,且 pr-gate 拦不住(只有 full-ci e2e 才现形)。
   *
   * <p>守护:console-api 下任何含 Spring stereotype bean 的 {@code console.<sub>} 顶层子包,都必须被
   * E2eConsoleImportApplication 的 basePackages 覆盖(mapper/web 由 MapperScan/自身包覆盖,同样计入校验)。
   */
  @Test
  void e2eConsoleAppScanCoversAllConsoleBeanPackages() {
    ComponentScan scan = E2eConsoleImportApplication.class.getAnnotation(ComponentScan.class);
    List<String> scanned = List.of(scan.basePackages());

    Set<String> uncovered =
        ALL_CLASSES.stream()
            .filter(c -> c.getPackageName().startsWith("io.github.pinpols.batch.console."))
            .filter(
                c ->
                    c.isAnnotatedWith("org.springframework.stereotype.Component")
                        || c.isAnnotatedWith("org.springframework.stereotype.Service")
                        || c.isAnnotatedWith("org.springframework.stereotype.Repository")
                        || c.isAnnotatedWith(
                            "org.springframework.context.annotation.Configuration"))
            .map(c -> c.getPackageName())
            .filter(p -> scanned.stream().noneMatch(s -> p.equals(s) || p.startsWith(s + ".")))
            // e2e 自己的测试包/console 主 app 包(被 excludeFilters 排除的入口类)不计
            .filter(p -> !p.startsWith("io.github.pinpols.batch.console.e2e"))
            .collect(Collectors.toCollection(TreeSet::new));

    Assertions.assertTrue(
        uncovered.isEmpty(),
        "console 下存在含 Spring bean 但未被 E2eConsoleImportApplication @ComponentScan 覆盖的包(会"
            + " NoSuchBean 启动失败,见 2026-07-09 main-red):"
            + uncovered
            + " → 在 E2eConsoleImportApplication.basePackages 里补齐,或移动 bean 到已扫描包。");
  }
}
