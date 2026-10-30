package io.github.pinpols.batch.console.domain.notification.param;

import java.util.Objects;
import lombok.Builder;

/** 通知投递日志写入参数；事件和渠道身份由实际投递路径提供。 */
@Builder
public record NotificationDeliveryLogInsertParam(
    String tenantId,
    Long ruleId,
    String channelCode,
    String eventType,
    Long alertEventId,
    String payloadJson,
    String deliveryStatus,
    String errorMessage,
    Integer attempt) {
  public NotificationDeliveryLogInsertParam {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(channelCode, "channelCode");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(payloadJson, "payloadJson");
    Objects.requireNonNull(deliveryStatus, "deliveryStatus");
    Objects.requireNonNull(attempt, "attempt");
  }
}
