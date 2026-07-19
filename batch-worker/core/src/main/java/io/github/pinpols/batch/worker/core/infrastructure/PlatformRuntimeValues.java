package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Platform 文件运行态 Mapper 的值转换与参数构造工具。 */
final class PlatformRuntimeValues {

  static final String PIPELINE_DEFINITION_ID = "pipelineDefinitionId";
  static final String PIPELINE_INSTANCE_ID = "pipelineInstanceId";
  static final String CURRENT_STAGE = "currentStage";
  static final String TENANT_ID = "tenantId";
  static final String FILE_ID = "fileId";
  static final String ID = "id";

  private PlatformRuntimeValues() {}

  static Map<String, Object> params(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }

  static Long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      return Long.valueOf(string);
    }
    return null;
  }

  static Integer toInteger(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      return Integer.valueOf(string);
    }
    return null;
  }

  static Instant toInstant(Object value) {
    return value instanceof Timestamp timestamp ? timestamp.toInstant() : null;
  }

  static String toJson(Object value) {
    return value == null ? null : JsonUtils.toJson(value);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> toMap(Object value) {
    if (value == null) {
      return Map.of();
    }
    if (value instanceof Map<?, ?> rawMap) {
      Map<String, Object> mapped = new LinkedHashMap<>();
      rawMap.forEach((key, rawValue) -> mapped.put(String.valueOf(key), rawValue));
      return mapped;
    }
    return JsonUtils.fromJson(String.valueOf(value), Map.class);
  }

  static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  static String defaultText(String value, String fallback) {
    return Texts.hasText(value) ? value : fallback;
  }

  static String truncate(String value, int maxLength) {
    return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
  }
}
