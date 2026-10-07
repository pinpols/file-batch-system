package io.github.pinpols.batch.common.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * batch-common 自身的规约守护。其它模块见各自 {@code *ConventionsArchTest},均复用 {@link CodingConventionsArchRules}
 * 中的规则。
 */
@DisplayName("batch-common 主源集规约守护:默认时区读取,字符集按名解析与持久化类命名后缀三条硬性约定")
class CodingConventionsArchTest {

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.common..");

  @Test
  @DisplayName("非时区基础设施类不得直接读取 JVM 默认时区,白名单仅时区配置与时间包")
  void shouldForbidDefaultTimezoneReads_whenOutsideTimezoneInfrastructure() {
    CodingConventionsArchRules.zoneIdSystemDefaultRule().check(CLASSES);
  }

  @Test
  @DisplayName("除编码工具类自身外,业务类不得按名称解析字符集,须改用标准编码常量")
  void shouldForbidEncodingLookupByName_whenOutsideEncodingUtils() {
    CodingConventionsArchRules.charsetForNameRule().check(CLASSES);
  }

  @Test
  @DisplayName("持久化与领域类的类名不得以 Record 结尾 (RecordEntity 与裸名 Record 除外)")
  void shouldForbidRecordSuffix_whenNamingPersistentOrDomainClasses() {
    CodingConventionsArchRules.recordSuffixForbiddenRule().check(CLASSES);
  }
}
