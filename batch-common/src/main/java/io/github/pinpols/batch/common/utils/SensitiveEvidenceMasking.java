package io.github.pinpols.batch.common.utils;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 面向只读证据的凭据脱敏；不修改原始存储数据。 */
public final class SensitiveEvidenceMasking {
  private SensitiveEvidenceMasking() {}

  /** 遮蔽端点和已识别的凭据表达式，普通文本原样保留。 */
  public static String maskText(String value) {
    String masked = SecretMasking.maskEndpoint(value);
    return SwallowedExceptionLogger.containsCredentialSyntax(masked)
        ? SwallowedExceptionLogger.safeMessage(masked, 2048)
        : masked;
  }

  /** 拷贝 JSON 对象，递归脱敏敏感键、端点和已识别的凭据表达式。 */
  public static Map<String, Object> maskMetadata(Map<String, Object> metadata) {
    if (EmptyChecks.isNull(metadata)) {
      return null;
    }
    Map<String, Object> masked = new LinkedHashMap<>();
    metadata.forEach((key, value) -> masked.put(
        key, SecretMasking.isSensitive(key) ? SecretMasking.MASKED_VALUE : maskJsonValue(value)));
    return masked;
  }

  /** 递归处理 JSON 对象、数组与文本，其余标量保持原类型。 */
  public static Object maskJsonValue(Object value) {
    if (value instanceof String text) {
      return maskText(text);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> masked = new LinkedHashMap<>();
      map.forEach((key, nested) -> {
        String name = String.valueOf(key);
        masked.put(
            name,
            SecretMasking.isSensitive(name) ? SecretMasking.MASKED_VALUE : maskJsonValue(nested));
      });
      return masked;
    }
    if (value instanceof List<?> list) {
      List<Object> masked = new ArrayList<>(list.size());
      list.forEach(item -> masked.add(maskJsonValue(item)));
      return masked;
    }
    return value;
  }
}
