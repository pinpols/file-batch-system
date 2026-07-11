package io.github.pinpols.batch.console.domain.workflow.web.response;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 将 workflow 域 MyBatis {@code resultType=map} 行 / 编排内部代理回传的 {@code Map<String,Object>} 转换为稳定响应字段。
 * 与批次1~3 各域 {@code *ResponseFieldReader} 同一约定，独立放在 workflow 域避免跨包 package-private 依赖。
 */
final class WorkflowResponseFieldReader {

  private WorkflowResponseFieldReader() {}

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
}
