package io.github.pinpols.batch.orchestrator.application.service.sensor;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.math.BigDecimal;
import java.util.Map;

/** sensor_spec JSONB Map 的取值帮手——容忍 String / Number 类型混入，统一返回 null / 默认。 */
public final class SensorSpecs {

  private SensorSpecs() {}

  public static String string(Map<String, Object> spec, String key) {
    if (spec == null) {
      return null;
    }
    Object v = spec.get(key);
    return v == null ? null : v.toString();
  }

  public static Long longValue(Map<String, Object> spec, String key) {
    if (spec == null) {
      return null;
    }
    Object v = spec.get(key);
    if (v == null) {
      return null;
    }
    try {
      if (v instanceof Number n) {
        return new BigDecimal(n.toString()).longValueExact();
      }
      String trimmedValue = v.toString().trim();
      return Long.parseLong(trimmedValue);
    } catch (NumberFormatException | ArithmeticException e) {
      return null;
    }
  }

  public static Integer intValue(Map<String, Object> spec, String key) {
    Long value = longValue(spec, key);
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    try {
      return Math.toIntExact(value);
    } catch (ArithmeticException overflow) {
      return null;
    }
  }
}
