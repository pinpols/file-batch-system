package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.utils.Texts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ADR-041 Phase1.4:出站内嵌 trailer 控制记录构造(纯函数,可独立单测)。按 {@code trailer_template} 把记录类型标记 / 声明笔数 /
 * 控制总额放到配置的列 index,组装成 trailer 行的各列原始值;由具体 format(delimited / fixed-width)负责拼接分隔符 / 定宽。
 *
 * <p>{@code trailer_template} 示例:
 *
 * <pre>{@code
 * {"present":true,"recordType":"T","recordTypeIndex":0,"recordCountIndex":1,
 *  "controlTotalIndex":2,"fieldCount":3,"amountField":"amount"}
 * }</pre>
 *
 * 与入站 {@code TrailerControlRecord}(import)对称:入站解析上游 trailer,出站生成给下游对账的 trailer。
 */
public final class OutboundTrailerRecord {

  private OutboundTrailerRecord() {}

  /** {@code trailer_template.present=true} 时启用出站 trailer。 */
  public static boolean enabled(Map<String, Object> trailerTemplate) {
    return trailerTemplate != null
        && !trailerTemplate.isEmpty()
        && boolOr(trailerTemplate.get("present"), false);
  }

  /** 控制总额累加的明细列名(amountField / amount_field);未配则不累加金额。 */
  public static String amountField(Map<String, Object> trailerTemplate) {
    if (trailerTemplate == null) {
      return null;
    }
    Object raw = trailerTemplate.get("amountField");
    if (raw == null) {
      raw = trailerTemplate.get("amount_field");
    }
    String text = raw == null ? null : String.valueOf(raw).trim();
    return Texts.hasText(text) ? text : null;
  }

  /**
   * 按模板把 recordType / recordCount / controlTotal 放进各自 index,返回 trailer 行的逐列字符串值。{@code
   * controlTotal} 为 null(未配金额列)时不写控制总额列。
   */
  public static List<String> buildValues(
      Map<String, Object> trailerTemplate, long recordCount, BigDecimal controlTotal) {
    int recordTypeIndex = intOr(trailerTemplate.get("recordTypeIndex"), 0);
    Integer recordCountIndex = intOrNull(trailerTemplate.get("recordCountIndex"));
    Integer controlTotalIndex = intOrNull(trailerTemplate.get("controlTotalIndex"));
    int explicitFieldCount = intOr(trailerTemplate.get("fieldCount"), 0);
    int maxIndex = recordTypeIndex;
    maxIndex = Math.max(maxIndex, recordCountIndex == null ? 0 : recordCountIndex);
    maxIndex = Math.max(maxIndex, controlTotalIndex == null ? 0 : controlTotalIndex);
    int fieldCount = Math.max(explicitFieldCount, maxIndex + 1);
    List<String> values = new ArrayList<>(Collections.nCopies(fieldCount, ""));
    String marker = strOr(trailerTemplate.get("recordType"), "T");
    setAt(values, recordTypeIndex, marker);
    if (recordCountIndex != null) {
      setAt(values, recordCountIndex, Long.toString(recordCount));
    }
    if (controlTotalIndex != null && controlTotal != null) {
      setAt(values, controlTotalIndex, controlTotal.toPlainString());
    }
    return values;
  }

  private static void setAt(List<String> values, int index, String value) {
    if (index >= 0 && index < values.size()) {
      values.set(index, value);
    }
  }

  private static String strOr(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String text = String.valueOf(value).trim();
    return Texts.hasText(text) ? text : fallback;
  }

  private static Integer intOrNull(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static int intOr(Object value, int fallback) {
    Integer parsed = intOrNull(value);
    return parsed == null ? fallback : parsed;
  }

  private static boolean boolOr(Object value, boolean fallback) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
  }
}
