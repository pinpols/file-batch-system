package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.security.SensitiveDataValidator;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 限制并清洗由调用方提供、将发送给外部模型的 JSON 上下文。 */
final class ConsoleAiContextSanitizer {

  private static final int MAX_DEPTH = 4;
  private static final int MAX_NODES = 128;
  private static final int MAX_KEY_CHARS = 64;
  private static final int HARD_MAX_CONTEXT_CHARS = 8000;

  private ConsoleAiContextSanitizer() {}

  static String sanitize(Map<String, Object> context, int maxChars) {
    if (EmptyChecks.isEmpty(context)) {
      return "{}";
    }
    if (maxChars <= 0) {
      throw invalid("context limit must be positive");
    }
    Object safeContext = sanitizeValue(context, 0, new int[] {0});
    String json = JsonUtils.toJson(safeContext);
    if (json.length() > Math.min(maxChars, HARD_MAX_CONTEXT_CHARS)) {
      throw invalid("context exceeds maximum serialized length");
    }
    return json;
  }

  private static Object sanitizeValue(Object value, int depth, int[] nodeCount) {
    if (++nodeCount[0] > MAX_NODES || depth > MAX_DEPTH) {
      throw invalid("context exceeds maximum structure size");
    }
    if (EmptyChecks.isNull(value) || value instanceof Boolean || value instanceof Number) {
      return value;
    }
    if (value instanceof String string) {
      return ConsoleTextSanitizer.safeInput(string);
    }
    if (value instanceof Map<?, ?> map) {
      SensitiveDataValidator.rejectIfContainsSensitiveKeys(castMap(map), "console.ai.context");
      Map<String, Object> sanitized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalid("context object keys must be strings");
        }
        String normalizedKey = ConsoleTextSanitizer.safeInput(key);
        if (EmptyChecks.isBlank(normalizedKey)
            || normalizedKey.length() > MAX_KEY_CHARS
            || !normalizedKey.equals(key)) {
          throw invalid("context object key is invalid");
        }
        sanitized.put(normalizedKey, sanitizeValue(entry.getValue(), depth + 1, nodeCount));
      }
      return sanitized;
    }
    if (value instanceof List<?> list) {
      List<Object> sanitized = new ArrayList<>(list.size());
      for (Object item : list) {
        sanitized.add(sanitizeValue(item, depth + 1, nodeCount));
      }
      return sanitized;
    }
    throw invalid("context values must contain only JSON data types");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> castMap(Map<?, ?> map) {
    for (Object key : map.keySet()) {
      if (!(key instanceof String)) {
        throw invalid("context object keys must be strings");
      }
    }
    return (Map<String, ?>) map;
  }

  private static BizException invalid(String detail) {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail", detail);
  }
}
