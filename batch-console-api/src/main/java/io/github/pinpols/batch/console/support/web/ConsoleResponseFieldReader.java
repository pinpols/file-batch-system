package io.github.pinpols.batch.console.support.web;

import io.github.pinpols.batch.common.utils.EmptyChecks;
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

/** Map 型 Console 响应共用的转换辅助方法。 */
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
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String numericText = value.toString();
    return Integer.valueOf(numericText);
  }

  public static Long longValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String numericText = value.toString();
    return Long.valueOf(numericText);
  }

  public static Boolean booleanValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String booleanText = value.toString();
    return Boolean.valueOf(booleanText);
  }

  public static BigDecimal bigDecimalValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      String numericText = number.toString();
      return new BigDecimal(numericText);
    }
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String numericText = value.toString();
    return new BigDecimal(numericText);
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
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String instantText = value.toString();
    return Instant.parse(instantText);
  }

  public static LocalDate localDateValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value instanceof LocalDate localDate) {
      return localDate;
    }
    if (value instanceof Date date) {
      return date.toLocalDate();
    }
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String dateText = value.toString();
    return LocalDate.parse(dateText);
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
      if (element instanceof Number number) {
        result.add(number.longValue());
      } else {
        String numericText = element.toString();
        Long parsedValue = Long.valueOf(numericText);
        result.add(parsedValue);
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Long> longMap(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    if (value == null) {
      return null;
    }
    Map<String, Long> result = new LinkedHashMap<>();
    ((Map<String, ?>) value).forEach((key, item) -> {
      Long convertedValue;
      if (item instanceof Number number) {
        convertedValue = number.longValue();
      } else if (EmptyChecks.isNull(item)) {
        convertedValue = null;
      } else {
        String numericText = item.toString();
        convertedValue = Long.valueOf(numericText);
      }
      result.put(key, convertedValue);
    });
    return result;
  }
}
