package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.imports.domain.CustomerImportPayload;
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
      return map.entrySet().stream()
          .collect(
              LinkedHashMap::new,
              (out, entry) -> out.put(String.valueOf(entry.getKey()), entry.getValue()),
              Map::putAll);
    }
    if (value == null) {
      return Map.of();
    }
    try {
      String jsonText = String.valueOf(value);
      Map<String, Object> parsedConfig = objectMapper.readValue(jsonText, new TypeReference<>() {});
      return parsedConfig;
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(ValidationConfigSupport.class, "catch:Exception", ignored);

      return Map.of();
    }
  }

  public Map<String, Object> firstMap(Map<String, Object> container, String... keys) {
    for (String key : keys) {
      Map<String, Object> candidate = toMap(container.get(key));
      if (EmptyChecks.isNotEmpty(candidate)) {
        return candidate;
      }
    }
    return Map.of();
  }

  public Map<String, Object> payloadToMap(CustomerImportPayload payload) {
    return objectMapper.convertValue(payload, new TypeReference<>() {});
  }
}
