package io.github.pinpols.batch.console.domain.observability.infrastructure;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.page.CursorCodec;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 控制台查询子服务的共享工具方法。 */
public final class ConsoleQuerySupport {

  private ConsoleQuerySupport() {}

  public static <S, T> PageResponse<T> page(
      PageRequest pageRequest, long total, List<S> rows, Function<S, T> mapper) {
    return new PageResponse<>(
        total, pageRequest.pageNo(), pageRequest.pageSize(), rows.stream().map(mapper).toList());
  }

  public static <S, T> PageResponse<T> cursorPage(
      PageRequest pageRequest, List<S> rows, Function<S, T> mapper, Function<S, Long> idExtractor) {
    List<T> items = rows.stream().map(mapper).toList();
    String nextCursor =
        rows.size() < pageRequest.pageSize() || rows.isEmpty()
            ? null
            : CursorCodec.encode(Map.of("id", idExtractor.apply(rows.get(rows.size() - 1))));
    return PageResponse.cursor(items, pageRequest.pageSize(), nextCursor);
  }

  public static Long decodeCursorId(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    Object raw = CursorCodec.decode(token).get("id");
    if (raw instanceof Number number) {
      return number.longValue();
    }
    if (raw instanceof String string) {
      try {
        return Long.parseLong(string);
      } catch (NumberFormatException ignored) {
        SwallowedExceptionLogger.info(
            ConsoleQuerySupport.class, "catch:NumberFormatException", ignored);
      }
    }
    return null;
  }

  public static <T> T requireNotNull(T value, String message) {
    if (value == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found_detail", message);
    }
    return value;
  }

  public static Map<String, Object> requireRow(Map<String, Object> row, String message) {
    if (row == null || row.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found_detail", message);
    }
    return row;
  }

  public static String resolveTenant(ConsoleTenantGuard tenantGuard, String requestTenantId) {
    return tenantGuard.resolveTenant(requestTenantId);
  }

  public static Instant parseInstant(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.field.iso_datetime_required", fieldName);
    }
  }

  /**
   * 控制台搜索框宽松解析：无 Z/偏移的 {@code yyyy-MM-dd}、{@code yyyy-MM-dd HH:mm:ss} 在 {@code zone} 下转为 {@link
   * Instant}。 宜传 {@code BatchTimezoneProvider.defaultZone()}，与 {@code batch.timezone.default-zone}
   * 对齐。
   */
  public static Instant parseFlexibleInstant(String value, String fieldName, ZoneId zone) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.length() == 10 && trimmed.charAt(4) == '-' && trimmed.charAt(7) == '-') {
      try {
        return LocalDate.parse(trimmed).atStartOfDay(zone).toInstant();
      } catch (DateTimeParseException ignored) {
      }
    }
    if (trimmed.endsWith("Z") || trimmed.contains("+") || trimmed.lastIndexOf('-') > 9) {
      try {
        return Instant.parse(trimmed);
      } catch (DateTimeParseException ignored) {
      }
    }
    try {
      return LocalDateTime.parse(trimmed.replace(' ', 'T')).atZone(zone).toInstant();
    } catch (DateTimeParseException ignored) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          fieldName + " must be ISO-8601 datetime, yyyy-MM-dd HH:mm:ss, or yyyy-MM-dd");
    }
  }

  /**
   * @see #parseFlexibleInstant(String, String, ZoneId)
   */
  public static Instant parseFlexibleInstantEndOfDay(String value, String fieldName, ZoneId zone) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      LocalDate date = LocalDate.parse(value);
      return date.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1);
    } catch (DateTimeParseException ignored) {
      SwallowedExceptionLogger.info(
          ConsoleQuerySupport.class, "catch:DateTimeParseException", ignored);
    }
    return parseFlexibleInstant(value, fieldName, zone);
  }

  public static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    return second;
  }

  public static LocalDate parseLocalDate(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.field.biz_date_format", fieldName);
    }
  }

  public static Long parseLong(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException exception) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.field.must_be_number", fieldName);
    }
  }

  public static String stringValue(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    return value == null ? null : ConsoleTextSanitizer.safeDisplay(String.valueOf(value));
  }

  public static String display(String value) {
    return value == null ? null : ConsoleTextSanitizer.safeDisplay(value);
  }

  public static LocalDate localDateValue(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof LocalDate localDate) {
      return localDate;
    }
    if (value instanceof java.sql.Date sqlDate) {
      return sqlDate.toLocalDate();
    }
    return LocalDate.parse(String.valueOf(value));
  }

  public static Long longValue(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  public static Integer intValue(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  public static Boolean booleanValue(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  public static Instant instantValue(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime.toInstant(ZoneOffset.UTC);
    }
    if (value instanceof OffsetDateTime offsetDateTime) {
      return offsetDateTime.toInstant();
    }
    if (value instanceof Date date) {
      return date.toInstant();
    }
    return Instant.parse(String.valueOf(value));
  }
}
