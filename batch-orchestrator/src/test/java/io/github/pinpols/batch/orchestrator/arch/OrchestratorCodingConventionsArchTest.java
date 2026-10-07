package io.github.pinpols.batch.orchestrator.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.common.arch.CodingConventionsArchRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** batch-orchestrator docs/agent-baseline.md 规约守护,规则源自 batch-common 测试 jar 的 CodingConventionsArchRules。 */
@DisplayName("编排模块编码规约守护: 默认时区与字符集用法, 类型命名后缀, 事件监听与定时任务的事务注解边界")
class OrchestratorCodingConventionsArchTest {

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.orchestrator..");

  @Test
  @DisplayName("编排模块生产代码不直接读取 JVM 默认时区, 规则校验无违规")
  void shouldFindNoJvmDefaultTimeZoneUsage_whenRuleChecked() {
    CodingConventionsArchRules.zoneIdSystemDefaultRule().check(CLASSES);
  }

  @Test
  @DisplayName("编排模块生产代码不按名称构造字符集, 规则校验无违规")
  void shouldFindNoNameBasedCharsetConstruction_whenRuleChecked() {
    CodingConventionsArchRules.charsetForNameRule().check(CLASSES);
  }

  @Test
  @DisplayName("编排模块内不存在以记录后缀结尾的类型名, 规则校验无违规")
  void shouldFindNoRecordSuffixTypeName_whenRuleChecked() {
    CodingConventionsArchRules.recordSuffixForbiddenRule().check(CLASSES);
  }

  @Test
  @DisplayName("事件监听方法上不叠加事务注解, 规则校验无违规")
  void shouldFindNoTransactionalEventListener_whenRuleChecked() {
    CodingConventionsArchRules.noTransactionalOnEventListenerRule().check(CLASSES);
  }

  @Test
  @DisplayName("定时任务方法上不叠加事务注解, 规则校验无违规")
  void shouldFindNoTransactionalScheduledMethod_whenRuleChecked() {
    CodingConventionsArchRules.noTransactionalOnScheduledRule().check(CLASSES);
  }
}
