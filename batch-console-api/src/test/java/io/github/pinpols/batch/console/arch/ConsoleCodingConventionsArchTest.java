package io.github.pinpols.batch.console.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.common.arch.CodingConventionsArchRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * batch-console-api 自有 docs/agent-baseline.md 规约守护。复用 batch-common 测试 jar 里的 {@link
 * CodingConventionsArchRules},把规则应用到 console-api 已编译类上。
 *
 * <p>历史上 console-api 是 ZoneId.systemDefault() 回归的重灾区(ConsoleJwtService 等), 加上本测试后 surefire
 * 阶段即可拦截,无需等评审。
 */
@DisplayName("控制台编码规约守护: 默认时区与字符集用法、类型命名后缀、事件监听与定时任务的事务注解边界")
class ConsoleCodingConventionsArchTest {

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.console..");

  @Test
  @DisplayName("生产代码不直接读取 JVM 默认时区, 规则校验无违规")
  void shouldAvoidJvmDefaultTimeZone_whenRuleChecked() {
    CodingConventionsArchRules.zoneIdSystemDefaultRule().check(CLASSES);
  }

  @Test
  @DisplayName("生产代码不按名称构造字符集, 规则校验无违规")
  void shouldAvoidCharsetLookupByName_whenRuleChecked() {
    CodingConventionsArchRules.charsetForNameRule().check(CLASSES);
  }

  @Test
  @DisplayName("生产代码内不存在以记录后缀结尾的类型名, 规则校验无违规")
  void shouldAvoidRecordSuffixTypeName_whenRuleChecked() {
    CodingConventionsArchRules.recordSuffixForbiddenRule().check(CLASSES);
  }

  @Test
  @DisplayName("事件监听方法上不叠加事务注解, 规则校验无违规")
  void shouldAvoidTransactionalOnEventListener_whenRuleChecked() {
    CodingConventionsArchRules.noTransactionalOnEventListenerRule().check(CLASSES);
  }

  @Test
  @DisplayName("定时任务方法上不叠加事务注解, 规则校验无违规")
  void shouldAvoidTransactionalOnScheduledMethod_whenRuleChecked() {
    CodingConventionsArchRules.noTransactionalOnScheduledRule().check(CLASSES);
  }
}
