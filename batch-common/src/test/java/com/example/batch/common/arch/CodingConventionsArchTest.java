package com.example.batch.common.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * batch-common 自身的规约守护。其它模块见各自 {@code *ConventionsArchTest},均复用 {@link CodingConventionsArchRules}
 * 中的规则。
 */
class CodingConventionsArchTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.example.batch.common..");

  @Test
  void zoneIdSystemDefault() {
    CodingConventionsArchRules.zoneIdSystemDefaultRule().check(CLASSES);
  }

  @Test
  void charsetForName() {
    CodingConventionsArchRules.charsetForNameRule().check(CLASSES);
  }
}
