package io.github.pinpols.batch.console.support.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small shared conversions for map-backed console application services. */
public final class ConsoleMapSupport {

  private ConsoleMapSupport() {}

  public static Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return value == null ? null : Long.valueOf(String.valueOf(value));
  }

  public static Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      result.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return result;
  }
}
