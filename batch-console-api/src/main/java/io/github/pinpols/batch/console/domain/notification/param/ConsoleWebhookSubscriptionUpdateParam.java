package io.github.pinpols.batch.console.domain.notification.param;

import lombok.Builder;

/** 绑定一次update操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleWebhookSubscriptionUpdateParam(
    String tenantId,
    Long id,
    String callbackUrl,
    String eventTypes,
    String secret,
    boolean enabled,
    String operator) {

  @Override
  public String toString() {
    // 持久化输入可能包含凭据或业务载荷，诊断信息不展开字段值。
    return "ConsoleWebhookSubscriptionUpdateParam[redacted]";
  }
}
