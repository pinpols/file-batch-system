package io.github.pinpols.batch.sdk.task;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One inline result version frozen into the claimed task payload. */
public record SdkCrossDayResult(
    Long resultVersionId,
    String businessKey,
    Integer versionNo,
    Long sourceJobInstanceId,
    Map<String, Object> output) {

  public SdkCrossDayResult {
    if (resultVersionId == null || resultVersionId <= 0) {
      throw new IllegalArgumentException("cross-day resultVersionId must be positive");
    }
    if (businessKey == null || businessKey.isBlank()) {
      throw new IllegalArgumentException("cross-day businessKey is required");
    }
    if (versionNo == null || versionNo <= 0) {
      throw new IllegalArgumentException("cross-day versionNo must be positive");
    }
    if (output == null) {
      throw new IllegalArgumentException("cross-day inline output must be an object");
    }
    output = freezeMap(output);
  }

  private static Map<String, Object> freezeMap(Map<String, ?> source) {
    Map<String, Object> frozen = new LinkedHashMap<>();
    source.forEach((key, value) -> frozen.put(key, freezeValue(value)));
    return Collections.unmodifiableMap(frozen);
  }

  private static Object freezeValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> frozen = new LinkedHashMap<>();
      map.forEach((key, nested) -> {
        if (!(key instanceof String text)) {
          throw new IllegalArgumentException("cross-day output keys must be strings");
        }
        frozen.put(text, freezeValue(nested));
      });
      return Collections.unmodifiableMap(frozen);
    }
    if (value instanceof List<?> list) {
      List<Object> frozen = new ArrayList<>(list.size());
      list.forEach(item -> frozen.add(freezeValue(item)));
      return Collections.unmodifiableList(frozen);
    }
    return value;
  }
}
