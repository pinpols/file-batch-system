package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.Texts;
import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ADR-041 Phase1.1:从文件 trailer 行 + {@code trailer_template} 抽出上游声明的记录数 / 控制总额。纯函数,可独立单测。
 *
 * <p>{@code trailer_template}(DELIMITED)示例:
 *
 * <pre>{@code
 * {"present":true,"delimiter":",","recordCountIndex":1,"controlTotalIndex":2}
 * }</pre>
 *
 * 解析失败 / 缺字段对应值返回 null(不抛),由校验侧(controlRecordCheck / controlTotalCheck)决定告警或拒绝。
 */
public final class TrailerControlRecord {

  /** ParseStep 剥离 trailer 后,把声明笔数 / 控制总额 stash 进 context attributes 的键。 */
  public static final String ATTR_DECLARED_RECORD_COUNT = "declaredRecordCount";

  public static final String ATTR_DECLARED_CONTROL_TOTAL = "declaredControlTotal";

  private final Long declaredRecordCount;
  private final BigDecimal declaredControlTotal;

  private TrailerControlRecord(Long declaredRecordCount, BigDecimal declaredControlTotal) {
    this.declaredRecordCount = declaredRecordCount;
    this.declaredControlTotal = declaredControlTotal;
  }

  public Long declaredRecordCount() {
    return declaredRecordCount;
  }

  public BigDecimal declaredControlTotal() {
    return declaredControlTotal;
  }

  public boolean isPresent() {
    return declaredRecordCount != null || declaredControlTotal != null;
  }

  /** trailer_template 是否声明了 trailer 行(present=true);ParseStep 用它决定是否剥离末行。 */
  public static boolean isPresentEnabled(Map<String, Object> trailerTemplate) {
    return trailerTemplate != null
        && !trailerTemplate.isEmpty()
        && boolOr(trailerTemplate.get("present"), false);
  }

  /** {@code trailerTemplate.present=true} 时按分隔符切 trailer 行取声明值;空模板 / 非 present 返回空记录。 */
  public static TrailerControlRecord parse(
      String trailerLine, Map<String, Object> trailerTemplate) {
    if (trailerLine == null
        || trailerTemplate == null
        || trailerTemplate.isEmpty()
        || !boolOr(trailerTemplate.get("present"), false)) {
      return new TrailerControlRecord(null, null);
    }
    String delimiter = strOr(trailerTemplate.get("delimiter"), ",");
    String[] fields = trailerLine.split(Pattern.quote(delimiter), -1);
    Long count = fieldLong(fields, intOr(trailerTemplate.get("recordCountIndex"), -1));
    BigDecimal total = fieldDecimal(fields, intOr(trailerTemplate.get("controlTotalIndex"), -1));
    return new TrailerControlRecord(count, total);
  }

  private static Long fieldLong(String[] fields, int index) {
    String raw = fieldAt(fields, index);
    if (!Texts.hasText(raw)) {
      return null;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(
          TrailerControlRecord.class, "catch:NumberFormatException", ignored);
      return null;
    }
  }

  private static BigDecimal fieldDecimal(String[] fields, int index) {
    String raw = fieldAt(fields, index);
    if (!Texts.hasText(raw)) {
      return null;
    }
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(
          TrailerControlRecord.class, "catch:NumberFormatException", ignored);
      return null;
    }
  }

  private static String fieldAt(String[] fields, int index) {
    return index < 0 || index >= fields.length ? null : fields[index];
  }

  private static String strOr(Object value, String fallback) {
    return value == null || !Texts.hasText(String.valueOf(value))
        ? fallback
        : String.valueOf(value);
  }

  private static int intOr(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static boolean boolOr(Object value, boolean fallback) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
  }
}
