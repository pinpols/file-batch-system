package io.github.pinpols.batch.console.domain.ops.web.response;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 将 ops 域 service 构建的 Map / MyBatis Map 行转换为稳定响应字段。与批次1/2 的 field reader 同一约定，独立放在 ops 域避免跨包
 * package-private 依赖。
 */
final class OpsResponseFieldReader {

  private OpsResponseFieldReader() {}

  static Object value(Map<String, ?> row, String... keys) {
    for (String key : keys) {
      if (row.containsKey(key)) {
        return row.get(key);
      }
    }
    return null;
  }

  static String stringValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value == null ? null : value.toString();
  }

  static Integer integerValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value instanceof Number number
        ? number.intValue()
        : value == null ? null : Integer.valueOf(value.toString());
  }

  static Long longValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value instanceof Number number
        ? number.longValue()
        : value == null ? null : Long.valueOf(value.toString());
  }

  static Boolean booleanValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value instanceof Boolean bool
        ? bool
        : value == null ? null : Boolean.valueOf(value.toString());
  }

  static Instant instantValue(Map<String, ?> row, String... keys) {
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

  static LocalDate localDateValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value instanceof LocalDate localDate) {
      return localDate;
    }
    if (value instanceof Date date) {
      return date.toLocalDate();
    }
    return value == null ? null : LocalDate.parse(value.toString());
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> mapList(Object value) {
    return value == null ? List.of() : (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(Object value) {
    return value == null ? null : (Map<String, Object>) value;
  }
}
