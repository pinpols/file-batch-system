package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.utils.Texts;
import java.util.Map;

final class DispatchChannelConfigSupport {

  private DispatchChannelConfigSupport() {}

  static String stringProp(Map<String, Object> map, String key) {
    Object value = map == null ? null : map.get(key);
    return value == null ? null : String.valueOf(value);
  }

  static int intProp(Map<String, Object> map, String key, int fallback) {
    Object value = map == null ? null : map.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value != null && Texts.hasText(String.valueOf(value))) {
      return Integer.parseInt(String.valueOf(value).trim());
    }
    return fallback;
  }
}
