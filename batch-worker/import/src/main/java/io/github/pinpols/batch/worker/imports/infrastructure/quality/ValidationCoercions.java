package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.Texts;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 不依赖 Spring/Jackson 的纯函数小工具，从 ImportDataQualityService 抽出。 */
final class ValidationCoercions {

  static final String KEY_MIN = "min";
  static final String KEY_MAX = "max";
  static final String KEY_ACTUAL = "actual";
  static final String KEY_REQUIRED = "required";
  static final String KEY_ALLOWED_VALUES = "allowedValues";
  static final String KEY_ERROR_CODE = "errorCode";
  static final String MSG_ACTUAL_SUFFIX = ", actual=";

  private ValidationCoercions() {}

  static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  static String defaultText(String value, String fallback) {
    return Texts.hasText(value) ? value : fallback;
  }

  static boolean booleanValue(Object value, boolean defaultValue) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value == null) {
      return defaultValue;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  static boolean enabled(Map<String, Object> rule) {
    return booleanValue(rule.get("enabled"), true);
  }

  static Integer integerValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Integer.valueOf(text);
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(
          ValidationCoercions.class, "catch:NumberFormatException", ignored);

      return null;
    }
  }

  static BigDecimal decimalValue(Object value) {
    if (value instanceof BigDecimal bigDecimal) {
      return bigDecimal;
    }
    if (value instanceof Number number) {
      // 经 number.toString() 而非 doubleValue():Long/BigInteger 超过 double 53 位精度时
      // doubleValue() 会静默丢精度,导致 range 校验(min/max 边界)对高精度金额/大整数误判。
      return new BigDecimal(number.toString());
    }
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(
          ValidationCoercions.class, "catch:NumberFormatException", ignored);

      return null;
    }
  }

  static List<String> stringList(Object value) {
    if (value instanceof Collection<?> collection) {
      List<String> items = new ArrayList<>();
      for (Object item : collection) {
        String text = stringValue(item);
        if (Texts.hasText(text)) {
          items.add(text);
        }
      }
      return items;
    }
    if (value == null) {
      return List.of();
    }
    String text = String.valueOf(value);
    if (!Texts.hasText(text)) {
      return List.of();
    }
    List<String> items = new ArrayList<>();
    for (String item : text.split(",")) {
      if (Texts.hasText(item)) {
        items.add(item.trim());
      }
    }
    return items;
  }

  static boolean containsIgnoreCase(List<String> candidates, String value) {
    for (String candidate : candidates) {
      if (candidate != null && candidate.equalsIgnoreCase(value)) {
        return true;
      }
    }
    return false;
  }

  static Object firstNonNull(Object... values) {
    for (Object value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  static String defaultErrorCode(String field, Map<String, Object> rule) {
    if (booleanValue(firstNonNull(rule.get(KEY_REQUIRED), rule.get("notNull")), false)) {
      return "IMPORT_VALIDATE_NULL";
    }
    if (firstNonNull(rule.get("minLength"), rule.get("maxLength")) != null) {
      return "IMPORT_VALIDATE_LENGTH";
    }
    if (firstNonNull(rule.get("regex"), rule.get("pattern")) != null) {
      return "IMPORT_VALIDATE_REGEX";
    }
    if (firstNonNull(rule.get(KEY_MIN), rule.get(KEY_MAX)) != null) {
      return "IMPORT_VALIDATE_RANGE";
    }
    if (firstNonNull(rule.get(KEY_ALLOWED_VALUES), rule.get("enum")) != null) {
      if ("customerType".equals(field)) {
        return "IMPORT_VALIDATE_TYPE_INVALID";
      }
      if ("status".equals(field)) {
        return "IMPORT_VALIDATE_STATUS_INVALID";
      }
      return "IMPORT_VALIDATE_ALLOWED_VALUES";
    }
    return "IMPORT_VALIDATE_RULE";
  }

  static String digest(String algorithm, String content) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance(defaultText(algorithm, "SHA-256"));
      byte[] hash = messageDigest.digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder();
      for (byte item : hash) {
        builder.append(String.format("%02x", item));
      }
      return builder.toString();
    } catch (Exception exception) {
      throw new IllegalStateException("unsupported checksum algorithm: " + algorithm, exception);
    }
  }
}
