package io.github.pinpols.batch.console.support.notification;

import com.fasterxml.jackson.databind.JsonNode;

/** 通知提供方签名实现共用的小型辅助方法。 */
public final class ConsoleNotificationCryptoSupport {

  private ConsoleNotificationCryptoSupport() {}

  public static String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      int highNibble = (value >> 4) & 0xF;
      int lowNibble = value & 0xF;
      char highDigit = Character.forDigit(highNibble, 16);
      char lowDigit = Character.forDigit(lowNibble, 16);
      result.append(highDigit).append(lowDigit);
    }
    return result.toString();
  }

  public static String textOrNull(JsonNode config, String field) {
    JsonNode value = config.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
