package io.github.pinpols.batch.console.domain.job.web.response;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将 MyBatis Map 行 / 下游 Map 响应转换为稳定响应字段，兼容 snake_case 与 camelCase 键。 与批次1 {@code
 * web.response.config.ResponseFieldReader} 同一约定，独立放在 job 域避免跨包 package-private 依赖。
 */
final class JobResponseFieldReader {

  private JobResponseFieldReader() {}

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

  /** TIME 列（start_time / end_time）：统一输出 {@code HH:mm[:ss]} 局部时间字符串。 */
  static String localTimeValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value instanceof Time time) {
      return time.toLocalTime().toString();
    }
    if (value instanceof LocalTime localTime) {
      return localTime.toString();
    }
    return value == null ? null : value.toString();
  }

  @SuppressWarnings("unchecked")
  static List<Long> longListValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value == null) {
      return List.of();
    }
    List<Long> result = new ArrayList<>();
    for (Object element : (List<Object>) value) {
      result.add(
          element instanceof Number number ? number.longValue() : Long.valueOf(element.toString()));
    }
    return result;
  }
}
