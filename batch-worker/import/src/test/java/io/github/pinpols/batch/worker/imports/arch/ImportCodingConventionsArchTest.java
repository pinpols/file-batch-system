package io.github.pinpols.batch.worker.imports.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.common.arch.CodingConventionsArchRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * batch-worker-import docs/agent-baseline.md 规约守护,规则源自 batch-common 测试 jar 的 CodingConventionsArchRules。
 *
 * <p>覆盖 P1-6 跨模块事务守卫:@EventListener / @Scheduled 方法禁直接叠 @Transactional(BizTableSchemaRegistrar
 * 所在模块)。
 */
@DisplayName("导入模块编码规约守护测试:事件监听与定时方法上的跨模块事务约束")
class ImportCodingConventionsArchTest {

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.worker.imports..");

  @Test
  @DisplayName("事件监听方法叠加事务注解时,规约检查判定为违规")
  void shouldRejectTransactional_whenEventListenerMethodAnnotated() {
    CodingConventionsArchRules.noTransactionalOnEventListenerRule().check(CLASSES);
  }

  @Test
  @DisplayName("定时方法叠加事务注解时,规约检查判定为违规")
  void shouldRejectTransactional_whenScheduledMethodAnnotated() {
    CodingConventionsArchRules.noTransactionalOnScheduledRule().check(CLASSES);
  }
}
