package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Platform 文件运行态值转换工具；固定 Mapper 参数由各自具名对象承载。 */
public final class PlatformRuntimeValues {

  private PlatformRuntimeValues() {}

  public static Long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      return Long.valueOf(string);
    }
    return null;
  }

  static Integer toInteger(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      return Integer.valueOf(string);
    }
    return null;
  }

  public static Instant toInstant(Object value) {
    return value instanceof Timestamp timestamp ? timestamp.toInstant() : null;
  }

  static String toJson(Object value) {
    return value == null ? null : JsonUtils.toJson(value);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> toMap(Object value) {
    if (value == null) {
      return Map.of();
    }
    if (value instanceof Map<?, ?> rawMap) {
      Map<String, Object> mapped = new LinkedHashMap<>();
      rawMap.forEach((key, rawValue) -> mapped.put(String.valueOf(key), rawValue));
      return mapped;
    }
    String jsonText = String.valueOf(value);
    return JsonUtils.fromJson(jsonText, Map.class);
  }

  static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  static String defaultText(String value, String fallback) {
    return Texts.hasText(value) ? value : fallback;
  }

  static String truncate(String value, int maxLength) {
    return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
  }
}
