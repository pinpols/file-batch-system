package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.imports.domain.CustomerImportPayload;
import java.util.Arrays;
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
      return objectMapper.readValue(String.valueOf(value), new TypeReference<>() {});
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(ValidationConfigSupport.class, "catch:Exception", ignored);

      return Map.of();
    }
  }

  public Map<String, Object> firstMap(Map<String, Object> container, String... keys) {
    return Arrays.stream(keys)
        .map(key -> toMap(container.get(key)))
        .filter(EmptyChecks::isNotEmpty)
        .findFirst()
        .orElseGet(Map::of);
  }

  public Map<String, Object> payloadToMap(CustomerImportPayload payload) {
    return objectMapper.convertValue(payload, new TypeReference<>() {});
  }
}
