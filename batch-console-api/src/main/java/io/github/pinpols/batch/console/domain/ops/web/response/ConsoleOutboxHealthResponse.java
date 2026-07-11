package io.github.pinpols.batch.console.domain.ops.web.response;

import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.stringValue;

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
    return new ConsoleOutboxHealthResponse(
        longValue(row, "pendingEvents"),
        longValue(row, "activeEvents"),
        longValue(row, "stalePublishingEvents"),
        mapList(row.get("deliveryStats")).stream().map(DeliveryStat::from).toList(),
        booleanValue(row, "healthy"));
  }
}
