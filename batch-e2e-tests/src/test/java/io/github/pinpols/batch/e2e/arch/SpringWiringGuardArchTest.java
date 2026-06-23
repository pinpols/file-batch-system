package io.github.pinpols.batch.e2e.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.common.arch.CodingConventionsArchRules;
import org.junit.jupiter.api.Test;

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
}
