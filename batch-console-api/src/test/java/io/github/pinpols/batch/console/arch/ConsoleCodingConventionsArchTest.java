package io.github.pinpols.batch.console.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.common.arch.CodingConventionsArchRules;
import org.junit.jupiter.api.Test;

/**
 * batch-console-api 自有 CLAUDE.md 规约守护。复用 batch-common 测试 jar 里的 {@link
 * CodingConventionsArchRules},把规则应用到 console-api 已编译类上。
 *
 * <p>历史上 console-api 是 ZoneId.systemDefault() 回归的重灾区(ConsoleJwtService 等), 加上本测试后 surefire
 * 阶段即可拦截,无需等评审。
 */
class ConsoleCodingConventionsArchTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.pinpols.batch.console..");

  @Test
  void zoneIdSystemDefault() {
    CodingConventionsArchRules.zoneIdSystemDefaultRule().check(CLASSES);
  }

  @Test
  void charsetForName() {
    CodingConventionsArchRules.charsetForNameRule().check(CLASSES);
  }

  @Test
  void recordSuffixForbidden() {
    CodingConventionsArchRules.recordSuffixForbiddenRule().check(CLASSES);
  }

  @Test
  void noTransactionalOnEventListener() {
    CodingConventionsArchRules.noTransactionalOnEventListenerRule().check(CLASSES);
  }

  @Test
  void noTransactionalOnScheduled() {
    CodingConventionsArchRules.noTransactionalOnScheduledRule().check(CLASSES);
  }
}
