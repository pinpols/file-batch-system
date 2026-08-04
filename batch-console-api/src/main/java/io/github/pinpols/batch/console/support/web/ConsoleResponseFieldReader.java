package io.github.pinpols.batch.console.support.web;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared conversion helpers for map-backed console responses. */
public class ConsoleResponseFieldReader {

  protected ConsoleResponseFieldReader() {}

  public static Object value(Map<String, ?> row, String... keys) {
    for (String key : keys) {
      if (row.containsKey(key)) {
        return row.get(key);
      }
    }
    return null;
  }

  public static String stringValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value == null ? null : value.toString();
  }

  public static Integer integerValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value instanceof Number number
        ? number.intValue()
        : value == null ? null : Integer.valueOf(value.toString());
  }

  public static Long longValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value instanceof Number number
        ? number.longValue()
        : value == null ? null : Long.valueOf(value.toString());
  }

  public static Boolean booleanValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value instanceof Boolean bool
        ? bool
        : value == null ? null : Boolean.valueOf(value.toString());
  }

  public static BigDecimal bigDecimalValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      return new BigDecimal(number.toString());
    }
    return value == null ? null : new BigDecimal(value.toString());
  }

  public static Instant instantValue(Map<String, ?> row, String... keys) {
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

  public static LocalDate localDateValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value instanceof LocalDate localDate) {
      return localDate;
    }
    if (value instanceof Date date) {
      return date.toLocalDate();
    }
    return value == null ? null : LocalDate.parse(value.toString());
  }

  public static String localTimeValue(Map<String, ?> row, String... keys) {
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
  public static List<Long> longListValue(Map<String, ?> row, String... keys) {
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

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> mapList(Object value) {
    return value == null ? List.of() : (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> mapList(Map<String, ?> row, String... keys) {
    return mapList(value(row, keys));
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> asMap(Object value) {
    return value == null ? null : (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Long> longMap(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value == null) {
      return null;
    }
    Map<String, Long> result = new LinkedHashMap<>();
    ((Map<String, ?>) value)
        .forEach((key, item) -> result.put(
            key,
            item instanceof Number number
                ? number.longValue()
                : item == null ? null : Long.valueOf(item.toString())));
    return result;
  }
}
