package io.github.pinpols.batch.console.domain.workflow.infrastructure.excel;

import static io.github.pinpols.batch.console.domain.workflow.infrastructure.excel.WorkflowExcelColumnMetadata.GUIDE_FALSE;
import static io.github.pinpols.batch.console.domain.workflow.infrastructure.excel.WorkflowExcelColumnMetadata.GUIDE_TRUE;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * P2-3 god-class-decomposition extract: parser + validator 共享的纯文本归一化助手。
 *
 * <p>原 service 内 6 个互相依赖的私有方法(normalize / hasText / normalizeEnum / parseInteger / parseBoolean /
 * tenantOrDefault)集中到本工具类。所有方法 static + null-safe,无任何 Spring/IO 依赖。
 */
public final class WorkflowExcelTextUtils {

  private WorkflowExcelTextUtils() {}

  static String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  static boolean hasText(String value) {
    return Texts.hasText(normalize(value));
  }

  static String tenantOrDefault(String value, String tenantId) {
    String normalized = normalize(value);
    return Texts.hasText(normalized) ? normalized : tenantId;
  }

  /**
   * 注意:原 service 实现是"upper case 后无论 allowed 是否包含都返回 upper",这里保持同义(避免行为漂移)。 真要 strict
   * enforce,validator 层会另外 fail row。
   */
  static String normalizeEnum(String value, Set<String> allowed) {
    String normalized = normalize(value);
    if (!Texts.hasText(normalized)) {
      return null;
    }
    return normalized.toUpperCase(Locale.ROOT);
  }

  static Integer parseInteger(String value) {
    String normalized = normalize(value);
    if (!Texts.hasText(normalized)) {
      return null;
    }
    try {
      return Integer.valueOf(normalized);
    } catch (NumberFormatException exception) {
      SwallowedExceptionLogger.info(
          WorkflowExcelTextUtils.class, "catch:NumberFormatException", exception);
      return null;
    }
  }

  static Boolean parseBoolean(String value, Boolean defaultValue) {
    String normalized = normalize(value);
    if (!Texts.hasText(normalized)) {
      return defaultValue;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (List.of(GUIDE_TRUE, "Y", "1", "YES").contains(upper)) {
      return true;
    }
    if (List.of(GUIDE_FALSE, "N", "0", "NO").contains(upper)) {
      return false;
    }
    return defaultValue;
  }
}
