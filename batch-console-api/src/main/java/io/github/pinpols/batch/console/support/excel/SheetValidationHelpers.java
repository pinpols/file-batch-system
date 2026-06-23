package io.github.pinpols.batch.console.support.excel;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.List;
import java.util.Set;

/**
 * Excel 单 sheet 行校验通用工具集。
 *
 * <p>各 Excel 应用服务 / Validator 在按行收集 issue 时，常重复写"X is required" / "X must be one of [...]" / "X
 * must be integer" / "X must be valid JSON" 这类校验。统一在这里，让错误消息模板一致、调用点更短。
 *
 * <p>所有方法约定：调用方传入已 normalize（去 BOM / 去首尾空白 / 去全角空白）的字符串值。本工具不再二次 normalize，只判断 blank（{@link
 * Texts#hasText} 语义）。
 */
public final class SheetValidationHelpers {

  private SheetValidationHelpers() {}

  /** "field is required" — 仅检查 blank。 */
  public static void requireField(List<String> ri, String value, String field) {
    if (!Texts.hasText(value)) {
      ri.add(field + " is required");
    }
  }

  /** "field is required" + "field must be one of allowed"。命中即抛错并返回。 */
  public static void requiredEnum(
      String value, String field, Set<String> allowed, List<String> ri) {
    if (!Texts.hasText(value)) {
      ri.add(field + " is required");
      return;
    }
    if (!allowed.contains(value)) {
      ri.add(field + " must be one of " + allowed);
    }
  }

  /** "if present, must be one of allowed" — 空白允许跳过。 */
  public static void optionalEnum(
      String value, String field, Set<String> allowed, List<String> ri) {
    if (Texts.hasText(value) && !allowed.contains(value)) {
      ri.add(field + " must be one of " + allowed);
    }
  }

  /** 整数字段：required=true 时空白报"is required"；非空必须可解析为 int。 */
  public static void requireIntField(String value, String field, List<String> ri) {
    if (!Texts.hasText(value)) {
      ri.add(field + " is required");
      return;
    }
    try {
      Integer.parseInt(value);
    } catch (NumberFormatException e) {
      SwallowedExceptionLogger.info(SheetValidationHelpers.class, "catch:NumberFormatException", e);

      ri.add(field + " must be integer");
    }
  }

  /** JSON 字段：required=true 时空白报错；非空必须可解析。 */
  public static void validateJsonField(
      String value, String field, boolean required, List<String> ri) {
    if (!Texts.hasText(value)) {
      if (required) {
        ri.add(field + " is required");
      }
      return;
    }
    try {
      JsonUtils.fromJson(value, Object.class);
    } catch (Exception e) {
      SwallowedExceptionLogger.warn(SheetValidationHelpers.class, "catch:Exception", e);

      ri.add(field + " must be valid JSON");
    }
  }
}
