package com.example.batch.worker.imports.infrastructure.quality;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 依赖 ObjectMapper 的 Map 转换辅助。 */
@Component
@RequiredArgsConstructor
public class ValidationConfigSupport {

  private final ObjectMapper objectMapper;

  public Map<String, Object> toMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> converted = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        converted.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      return converted;
    }
    if (value == null) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(String.valueOf(value), new TypeReference<>() {});
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(ValidationConfigSupport.class, "catch:Exception", ignored);

      return Map.of();
    }
  }

  public Map<String, Object> firstMap(Map<String, Object> container, String... keys) {
    for (String key : keys) {
      Map<String, Object> rule = toMap(container.get(key));
      if (!rule.isEmpty()) {
        return rule;
      }
    }
    return Map.of();
  }

  public Map<String, Object> payloadToMap(CustomerImportPayload payload) {
    return objectMapper.convertValue(payload, new TypeReference<>() {});
  }
}
