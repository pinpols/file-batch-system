package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import java.util.List;

/**
 * 集群诊断 - Outbox 健康。{@code deliveryStatus} 可为 null，保留历史响应中的 null 字段。
 */
public record ConsoleOutboxHealthResponse(
    Long pendingEvents,
    Long activeEvents,
    Long stalePublishingEvents,
    List<DeliveryStat> deliveryStats,
    Boolean healthy) {

  public record DeliveryStat(String deliveryStatus, Long cnt) {}
}
