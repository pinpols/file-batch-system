package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.enums.FileReceiptPolicy;
import java.util.Map;
import java.util.UUID;

final class DispatchReceiptSupport {

  private DispatchReceiptSupport() {}

  static Receipt resolve(
      DispatchCommand command, Map<String, Object> channelConfig, String defaultPolicy) {
    String receiptPolicy =
        String.valueOf(channelConfig.getOrDefault("receipt_policy", defaultPolicy));
    String externalRequestId;
    if (hasText(command.payload().externalRequestId())) {
      externalRequestId = command.payload().externalRequestId();
    } else {
      UUID generatedRequestId = UUID.randomUUID();
      externalRequestId = generatedRequestId.toString();
    }
    String receiptCode = hasText(command.payload().receiptCode())
        ? command.payload().receiptCode()
        : "R-" + externalRequestId;
    boolean acknowledged = FileReceiptPolicy.NONE.code().equalsIgnoreCase(receiptPolicy)
        || FileReceiptPolicy.SYNC.code().equalsIgnoreCase(receiptPolicy);
    boolean pending = FileReceiptPolicy.ASYNC.code().equalsIgnoreCase(receiptPolicy)
        || FileReceiptPolicy.POLLING.code().equalsIgnoreCase(receiptPolicy);
    return new Receipt(externalRequestId, receiptCode, acknowledged, pending);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  record Receipt(
      String externalRequestId, String receiptCode, boolean acknowledged, boolean pending) {}
}
