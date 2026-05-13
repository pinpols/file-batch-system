package com.example.batch.orchestrator.application.service.sensor;

import java.util.Map;

/** sensor_spec JSONB Map 的取值帮手——容忍 String / Number 类型混入，统一返回 null / 默认。 */
final class SensorSpecs {

  private SensorSpecs() {}

  static String string(Map<String, Object> spec, String key) {
    if (spec == null) {
      return null;
    }
    Object v = spec.get(key);
    return v == null ? null : v.toString();
  }

  static Long longValue(Map<String, Object> spec, String key) {
    if (spec == null) {
      return null;
    }
    Object v = spec.get(key);
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(v.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static Integer intValue(Map<String, Object> spec, String key) {
    Long l = longValue(spec, key);
    return l == null ? null : l.intValue();
  }
}
