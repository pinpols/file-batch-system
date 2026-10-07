package io.github.pinpols.batch.trigger.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.common.arch.CodingConventionsArchRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** batch-trigger docs/agent-baseline.md 规约守护,规则源自 batch-common 测试 jar 的 CodingConventionsArchRules。 */
@DisplayName("batch-trigger 编码规约守护:ArchUnit 校验时区/字符集/命名后缀与事务注解约束")
class TriggerCodingConventionsArchTest {

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.trigger..");

  @Test
  @DisplayName("trigger 包内禁止调用 ZoneId.systemDefault(),时区须由注入的 BatchTimezoneProvider 提供")
  void zoneIdSystemDefault_isForbidden() {
    CodingConventionsArchRules.zoneIdSystemDefaultRule().check(CLASSES);
  }

  @Test
  @DisplayName("trigger 包内禁止调用 Charset.forName(...),字符集统一用 StandardCharsets.UTF_8 或 EncodingUtils")
  void charsetForName_isForbidden() {
    CodingConventionsArchRules.charsetForNameRule().check(CLASSES);
  }

  @Test
  @DisplayName("禁止 *Record 结尾的持久化/领域类,统一改 *Entity 后缀(裸名 Record 与 *RecordEntity 豁免)")
  void recordSuffix_isForbidden() {
    CodingConventionsArchRules.recordSuffixForbiddenRule().check(CLASSES);
  }

  @Test
  @DisplayName("禁止 @EventListener 方法直接叠加 @Transactional,监听不走 Service 代理须改用 TransactionTemplate")
  void transactionalOnEventListener_isForbidden() {
    CodingConventionsArchRules.noTransactionalOnEventListenerRule().check(CLASSES);
  }

  @Test
  @DisplayName("禁止 @Scheduled 方法直接叠加 @Transactional,事务应抽到 Service 方法或用 TransactionTemplate")
  void transactionalOnScheduled_isForbidden() {
    CodingConventionsArchRules.noTransactionalOnScheduledRule().check(CLASSES);
  }
}
