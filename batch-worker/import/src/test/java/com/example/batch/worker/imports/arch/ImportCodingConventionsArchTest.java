package com.example.batch.worker.imports.arch;

import com.example.batch.common.arch.CodingConventionsArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * batch-worker-import CLAUDE.md 规约守护,规则源自 batch-common 测试 jar 的 CodingConventionsArchRules。
 *
 * <p>覆盖 P1-6 跨模块事务守卫:@EventListener / @Scheduled 方法禁直接叠 @Transactional(BizTableSchemaRegistrar
 * 所在模块)。
 */
class ImportCodingConventionsArchTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.example.batch.worker.imports..");

  @Test
  void noTransactionalOnEventListener() {
    CodingConventionsArchRules.noTransactionalOnEventListenerRule().check(CLASSES);
  }

  @Test
  void noTransactionalOnScheduled() {
    CodingConventionsArchRules.noTransactionalOnScheduledRule().check(CLASSES);
  }
}
