package io.github.pinpols.batch.console.web.response.config;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

/** 将 MyBatis Map 行转换为稳定响应字段，兼容 snake_case 与 camelCase 列标签。 */
final class ResponseFieldReader {

  private ResponseFieldReader() {}

  static Object value(Map<String, Object> row, String... keys) {
    for (String key : keys) {
      if (row.containsKey(key)) {
        return row.get(key);
      }
    }
    return null;
  }

  static String stringValue(Map<String, Object> row, String... keys) {
    Object value = value(row, keys);
    return value == null ? null : value.toString();
  }

  static Integer integerValue(Map<String, Object> row, String... keys) {
    Object value = value(row, keys);
    return value instanceof Number number
        ? number.intValue()
        : value == null ? null : Integer.valueOf(value.toString());
  }

  static Long longValue(Map<String, Object> row, String... keys) {
    Object value = value(row, keys);
    return value instanceof Number number
        ? number.longValue()
        : value == null ? null : Long.valueOf(value.toString());
  }

  static Boolean booleanValue(Map<String, Object> row, String... keys) {
    Object value = value(row, keys);
    return value instanceof Boolean bool
        ? bool
        : value == null ? null : Boolean.valueOf(value.toString());
  }

  static Instant instantValue(Map<String, Object> row, String... keys) {
    Object value = value(row, keys);
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof OffsetDateTime offsetDateTime) {
      return offsetDateTime.toInstant();
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    return value == null ? null : Instant.parse(value.toString());
  }
}
