package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import java.util.Map;
import java.util.UUID;

final class DispatchReceiptSupport {

  private DispatchReceiptSupport() {}

  static Receipt resolve(
      DispatchCommand command, Map<String, Object> channelConfig, String defaultPolicy) {
    String receiptPolicy =
        String.valueOf(channelConfig.getOrDefault("receipt_policy", defaultPolicy));
    String externalRequestId = hasText(command.payload().externalRequestId())
        ? command.payload().externalRequestId()
        : UUID.randomUUID().toString();
    String receiptCode = hasText(command.payload().receiptCode())
        ? command.payload().receiptCode()
        : "R-" + externalRequestId;
    boolean acknowledged =
        "NONE".equalsIgnoreCase(receiptPolicy) || "SYNC".equalsIgnoreCase(receiptPolicy);
    boolean pending =
        "ASYNC".equalsIgnoreCase(receiptPolicy) || "POLLING".equalsIgnoreCase(receiptPolicy);
    return new Receipt(externalRequestId, receiptCode, acknowledged, pending);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  record Receipt(
      String externalRequestId, String receiptCode, boolean acknowledged, boolean pending) {}
}
