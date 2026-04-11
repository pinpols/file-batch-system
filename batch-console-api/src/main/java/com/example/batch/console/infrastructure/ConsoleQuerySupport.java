package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.console.support.ConsoleTenantGuard;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 控制台查询子服务的共享工具方法。 */
final class ConsoleQuerySupport {

    private ConsoleQuerySupport() {}

    static <S, T> PageResponse<T> page(
            PageRequest pageRequest, long total, List<S> rows, Function<S, T> mapper) {
        return new PageResponse<>(
                total,
                pageRequest.pageNo(),
                pageRequest.pageSize(),
                rows.stream().map(mapper).toList());
    }

    static <T> T requireNotNull(T value, String message) {
        if (value == null) {
            throw new BizException(ResultCode.NOT_FOUND, message);
        }
        return value;
    }

    static String resolveTenant(ConsoleTenantGuard tenantGuard, String requestTenantId) {
        return tenantGuard.resolveTenant(requestTenantId);
    }

    static Instant parseInstant(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BizException(
                    ResultCode.INVALID_ARGUMENT, fieldName + " must be ISO-8601 datetime");
        }
    }

    static Instant parseFlexibleInstant(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException exception) {
            throw new BizException(
                    ResultCode.INVALID_ARGUMENT,
                    fieldName + " must be ISO-8601 datetime, yyyy-MM-dd HH:mm:ss, or yyyy-MM-dd");
        }
    }

    static Instant parseFlexibleInstantEndOfDay(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusMillis(1);
        } catch (DateTimeParseException ignored) {
        }
        return parseFlexibleInstant(value, fieldName);
    }

    static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    static LocalDate parseLocalDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, fieldName + " must use yyyy-MM-dd");
        }
    }

    static Long parseLong(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, fieldName + " must be a number");
        }
    }

    static String stringValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : ConsoleTextSanitizer.safeDisplay(String.valueOf(value));
    }

    static String display(String value) {
        return value == null ? null : ConsoleTextSanitizer.safeDisplay(value);
    }

    static LocalDate localDateValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    static Long longValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    static Integer intValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    static Boolean booleanValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    static Instant instantValue(Map<String, Object> row, String key) {
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
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }
}
