package io.github.pinpols.batch.console.domain.observability.web.response;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 dashboard / 诊断 service 在内存里构建的 {@code Map<String,Object>}（值已被上游 view 归一为 Instant / Long /
 * BigDecimal / LocalDate / String）转换为稳定响应字段。与批次1 {@code
 * web.response.config.ResponseFieldReader}、批次2 {@code job.web.response.JobResponseFieldReader}
 * 同一约定， 独立放在 observability 域避免跨包 package-private 依赖。
 */
final class ObservabilityResponseFieldReader {

  private ObservabilityResponseFieldReader() {}

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

  /** {@code byStatus}（status → count）为真动态维度键映射，原样透传为 {@code Map<String,Long>}。 */
  @SuppressWarnings("unchecked")
  static Map<String, Long> longMap(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value == null) {
      return null;
    }
    Map<String, Long> result = new LinkedHashMap<>();
    ((Map<String, ?>) value)
        .forEach(
            (k, v) ->
                result.put(
                    k,
                    v instanceof Number number
                        ? number.longValue()
                        : v == null ? null : Long.valueOf(v.toString())));
    return result;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(Object value) {
    return value == null ? null : (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> mapList(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value == null ? List.of() : (List<Map<String, Object>>) value;
  }
}
