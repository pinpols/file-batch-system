package io.github.pinpols.batch.common.utils;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 面向只读证据的凭据脱敏；不修改原始存储数据。 */
public final class SensitiveEvidenceMasking {

  /** 含凭据语法的证据文本遮罩后的长度上限，限制只读证据回显篇幅；不对应数据库列。 */
  private static final int CREDENTIAL_TEXT_MAX_LENGTH = 2048;

  private SensitiveEvidenceMasking() {}

  /** 遮蔽端点和已识别的凭据表达式，普通文本原样保留。 */
  public static String maskText(String value) {
    String masked = SecretMasking.maskEndpoint(value);
    return SwallowedExceptionLogger.containsCredentialSyntax(masked)
        ? SwallowedExceptionLogger.safeMessage(masked, CREDENTIAL_TEXT_MAX_LENGTH)
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

  /** 对 JSON 文本做结构化脱敏；非 JSON 文本按自由文本处理。无效 JSON 不原样回显凭据语法。 */
  public static String maskJsonOrText(String value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String trimmed = value.stripLeading();
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      try {
        Object parsed = JsonUtils.fromJson(value, Object.class);
        return JsonUtils.toJson(maskJsonValue(parsed));
      } catch (IllegalArgumentException ignored) {
        // 非法或历史 JSON 按自由文本兜底，不把原文直接当作安全文本返回。
      }
    }
    return maskText(value);
  }

  /** 递归处理 JSON 对象、数组与文本，其余标量保持原类型。 */
  public static Object maskJsonValue(Object value) {
    if (value instanceof String text) {
      return maskJsonOrText(text);
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
