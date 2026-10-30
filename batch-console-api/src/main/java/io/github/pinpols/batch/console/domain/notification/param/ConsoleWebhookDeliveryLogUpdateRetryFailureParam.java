package io.github.pinpols.batch.console.domain.notification.param;

import java.time.Instant;
import lombok.Builder;

/** 绑定一次updateRetryFailure操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleWebhookDeliveryLogUpdateRetryFailureParam(
    Long id,
    int attempt,
    Integer httpStatus,
    String responseBody,
    Instant nextRetryAt,
    String deliveryStatus) {

  @Override
  public String toString() {
    // 持久化输入可能包含凭据或业务载荷，诊断信息不展开字段值。
    return "ConsoleWebhookDeliveryLogUpdateRetryFailureParam[redacted]";
  }
}
