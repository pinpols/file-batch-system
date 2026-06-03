package com.example.batch.trigger.arch;

import com.example.batch.common.arch.CodingConventionsArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** batch-trigger CLAUDE.md 规约守护,规则源自 batch-common 测试 jar 的 CodingConventionsArchRules。 */
class TriggerCodingConventionsArchTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.example.batch.trigger..");

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
}
