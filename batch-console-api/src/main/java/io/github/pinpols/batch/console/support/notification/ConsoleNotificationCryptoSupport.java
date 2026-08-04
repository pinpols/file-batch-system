package io.github.pinpols.batch.console.support.notification;

import com.fasterxml.jackson.databind.JsonNode;

/** Shared small helpers used by notification provider signing implementations. */
public final class ConsoleNotificationCryptoSupport {

  private ConsoleNotificationCryptoSupport() {}

  public static String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result
          .append(Character.forDigit((value >> 4) & 0xF, 16))
          .append(Character.forDigit(value & 0xF, 16));
    }
    return result.toString();
  }

  public static String textOrNull(JsonNode config, String field) {
    JsonNode value = config.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
