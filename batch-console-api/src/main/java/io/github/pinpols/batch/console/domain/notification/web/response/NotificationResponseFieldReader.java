package io.github.pinpols.batch.console.domain.notification.web.response;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 将 notification 域 MyBatis {@code resultType=map} 行 / in-code 构建的 {@code Map<String,Object>}
 * 转换为稳定响应字段。 与批次1 {@code web.response.config.ResponseFieldReader}、批次2 {@code
 * job.web.response.JobResponseFieldReader}、批次3 {@code
 * observability.web.response.ObservabilityResponseFieldReader} 同一约定，独立放在 notification 域避免跨包
 * package-private 依赖。
 */
final class NotificationResponseFieldReader {

  private NotificationResponseFieldReader() {}

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

  static BigDecimal bigDecimalValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value instanceof BigDecimal bigDecimal) {
      return bigDecimal;
    }
    if (value instanceof Number number) {
      return new BigDecimal(number.toString());
    }
    return value == null ? null : new BigDecimal(value.toString());
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
