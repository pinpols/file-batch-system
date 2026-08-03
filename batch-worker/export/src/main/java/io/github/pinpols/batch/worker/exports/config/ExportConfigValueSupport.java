package io.github.pinpols.batch.worker.exports.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.PostgresqlJsonbTexts;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared normalization for export template JSON values and numeric settings. */
public final class ExportConfigValueSupport {

  private ExportConfigValueSupport() {}

  @SuppressWarnings("unchecked")
  public static Map<String, Object> toMap(Object raw, ObjectMapper objectMapper, Class<?> owner) {
    if (raw instanceof Map<?, ?> map) {
      Map<String, Object> converted = new LinkedHashMap<>();
      map.forEach((key, value) -> converted.put(String.valueOf(key), value));
      return converted;
    }
    String text = raw instanceof String value ? value : PostgresqlJsonbTexts.tryExtract(raw);
    if (!Texts.hasText(text)) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(text, Map.class);
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(owner, "catch:Exception", ignored);
      return Map.of();
    }
  }

  public static int resolveTemplateInt(ExportJobContext context, String key, int fallback) {
    Object config =
        context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (!(config instanceof Map<?, ?> templateConfig)) {
      return fallback;
    }
    Object value = templateConfig.get(key);
    if (value instanceof Number number) {
      return Math.max(1, number.intValue());
    }
    if (value != null && Texts.hasText(String.valueOf(value))) {
      return Math.max(1, Integer.parseInt(String.valueOf(value)));
    }
    return fallback;
  }
}
