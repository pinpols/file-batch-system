package io.github.pinpols.batch.orchestrator.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.common.arch.CodingConventionsArchRules;
import org.junit.jupiter.api.Test;

/** batch-orchestrator CLAUDE.md 规约守护,规则源自 batch-common 测试 jar 的 CodingConventionsArchRules。 */
class OrchestratorCodingConventionsArchTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.pinpols.batch.orchestrator..");

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
