package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.stringValue;

import java.util.List;
import java.util.Map;

/**
 * 集群诊断 - Outbox 健康。{@code deliveryStats} 由 service 用 LinkedHashMap 显式 put（deliveryStatus 可为 null）→
 * 不加 {@code NON_NULL}。
 */
public record ConsoleOutboxHealthResponse(
    Long pendingEvents,
    Long activeEvents,
    Long stalePublishingEvents,
    List<DeliveryStat> deliveryStats,
    Boolean healthy) {

  public record DeliveryStat(String deliveryStatus, Long cnt) {
    static DeliveryStat from(Map<String, Object> row) {
      return new DeliveryStat(stringValue(row, "deliveryStatus"), longValue(row, "cnt"));
    }
  }

  public static ConsoleOutboxHealthResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    Long pendingEvents = longValue(row, "pendingEvents");
    Long activeEvents = longValue(row, "activeEvents");
    Long stalePublishingEvents = longValue(row, "stalePublishingEvents");
    Object rawDeliveryStats = row.get("deliveryStats");
    List<Map<String, Object>> deliveryStatRows = mapList(rawDeliveryStats);
    List<DeliveryStat> deliveryStats = deliveryStatRows.stream().map(DeliveryStat::from).toList();
    return new ConsoleOutboxHealthResponse(
        pendingEvents,
        activeEvents,
        stalePublishingEvents,
        deliveryStats,
        booleanValue(row, "healthy"));
  }
}
