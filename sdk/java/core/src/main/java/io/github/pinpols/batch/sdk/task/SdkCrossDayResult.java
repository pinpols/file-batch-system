package io.github.pinpols.batch.sdk.task;

import io.github.pinpols.batch.sdk.internal.EmptyChecks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 领取任务载荷中冻结的单个内联结果版本。 */
public record SdkCrossDayResult(
    Long resultVersionId,
    String businessKey,
    Integer versionNo,
    Long sourceJobInstanceId,
    Map<String, Object> output) {

  public SdkCrossDayResult {
    if (EmptyChecks.isNull(resultVersionId) || resultVersionId <= 0) {
      throw new IllegalArgumentException("cross-day resultVersionId must be positive");
    }
    if (EmptyChecks.isBlank(businessKey)) {
      throw new IllegalArgumentException("cross-day businessKey is required");
    }
    if (EmptyChecks.isNull(versionNo) || versionNo <= 0) {
      throw new IllegalArgumentException("cross-day versionNo must be positive");
    }
    if (EmptyChecks.isNull(output)) {
      throw new IllegalArgumentException("cross-day inline output must be an object");
    }
    output = freezeMap(output);
  }

  private static Map<String, Object> freezeMap(Map<String, ?> source) {
    Map<String, Object> frozen = new LinkedHashMap<>();
    source.forEach((key, value) -> {
      Object frozenValue = freezeValue(value);
      frozen.put(key, frozenValue);
    });
    return Collections.unmodifiableMap(frozen);
  }

  private static Object freezeValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> frozen = new LinkedHashMap<>();
      map.forEach((key, nested) -> {
        if (!(key instanceof String text)) {
          throw new IllegalArgumentException("cross-day output keys must be strings");
        }
        Object frozenValue = freezeValue(nested);
        frozen.put(text, frozenValue);
      });
      return Collections.unmodifiableMap(frozen);
    }
    if (value instanceof List<?> list) {
      List<Object> frozen = new ArrayList<>(list.size());
      list.forEach(item -> {
        Object frozenItem = freezeValue(item);
        frozen.add(frozenItem);
      });
      return Collections.unmodifiableList(frozen);
    }
    return value;
  }
}
