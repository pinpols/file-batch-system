package io.github.pinpols.batch.console.domain.ops.web.response;

import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.asMap;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.localDateValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.stringValue;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 单实例深度诊断响应（{@code GET /cluster-diagnostic/instances/{id}}）。
 *
 * <p>{@code instance} / {@code summary} 的标量与列表由 service 用 LinkedHashMap 显式 put（键恒定，值可 null）→ 不加
 * {@code NON_NULL}。嵌套的分区 / 任务 / outbox 状态计数行来自 MyBatis {@code resultType=map}（省略 null 列）→ 具名行
 * record 加 {@code NON_NULL}。{@code findings[].evidence} 每条依据 finding 类型携带异构键（instanceStatus /
 * activePartitions / workerGroup / statusCounts / 任务行…），属真动态负载 → 保留 {@code
 * Map<String,Object>}（additionalProperties）。
 */
public record ConsoleInstanceDiagnosisResponse(
    String tenantId,
    Long jobInstanceId,
    Boolean healthy,
    InstanceSummary instance,
    DiagnosisSummary summary,
    List<Finding> findings) {

  /** job_instance 摘要（选列自 selectJobInstanceSummary；键恒定，值可 null）。 */
  public record InstanceSummary(
      Long id,
      String instanceNo,
      String jobCode,
      LocalDate bizDate,
      String instanceStatus,
      String queueCode,
      String workerGroup,
      String traceId,
      Instant startedAt,
      Instant deadlineAt,
      Instant now) {
    static InstanceSummary from(Map<String, Object> row) {
      if (row == null) {
        return null;
      }
      return new InstanceSummary(
          longValue(row, "id"),
          stringValue(row, "instanceNo"),
          stringValue(row, "jobCode"),
          localDateValue(row, "bizDate"),
          stringValue(row, "instanceStatus"),
          stringValue(row, "queueCode"),
          stringValue(row, "workerGroup"),
          stringValue(row, "traceId"),
          instantValue(row, "startedAt"),
          instantValue(row, "deadlineAt"),
          instantValue(row, "now"));
    }
  }

  /** 分区 / 任务 / outbox 状态计数 + 组内在线 worker 数。 */
  public record DiagnosisSummary(
      List<StatusCount> partitionStatusCounts,
      List<StatusCount> taskStatusCounts,
      List<OutboxStatusCount> outboxStatusCounts,
      Long onlineWorkersForGroup) {
    static DiagnosisSummary from(Map<String, Object> row) {
      if (row == null) {
        return null;
      }
      return new DiagnosisSummary(
          mapList(row.get("partitionStatusCounts")).stream().map(StatusCount::from).toList(),
          mapList(row.get("taskStatusCounts")).stream().map(StatusCount::from).toList(),
          mapList(row.get("outboxStatusCounts")).stream().map(OutboxStatusCount::from).toList(),
          longValue(row, "onlineWorkersForGroup"));
    }
  }

  /** 状态 → 计数行（MyBatis map，status 可为 null 时省略键）。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record StatusCount(String status, Long count) {
    static StatusCount from(Map<String, Object> row) {
      return new StatusCount(stringValue(row, "status"), longValue(row, "count"));
    }
  }

  /** outbox 状态 → 计数 + 最旧 / 最新时间（MyBatis map，省略 null 列）。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OutboxStatusCount(String status, Long count, Instant oldest, Instant newest) {
    static OutboxStatusCount from(Map<String, Object> row) {
      return new OutboxStatusCount(
          stringValue(row, "status"),
          longValue(row, "count"),
          instantValue(row, "oldest"),
          instantValue(row, "newest"));
    }
  }

  /** 诊断发现项。{@code evidence} 为真动态负载 → 保留 Map。 */
  public record Finding(
      String severity,
      String reasonCode,
      String message,
      List<String> suggestedActions,
      Map<String, Object> evidence) {
    @SuppressWarnings("unchecked")
    static Finding from(Map<String, Object> row) {
      Object actions = row.get("suggestedActions");
      return new Finding(
          stringValue(row, "severity"),
          stringValue(row, "reasonCode"),
          stringValue(row, "message"),
          actions == null ? List.of() : (List<String>) actions,
          asMap(row.get("evidence")));
    }
  }

  public static ConsoleInstanceDiagnosisResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleInstanceDiagnosisResponse(
        stringValue(row, "tenantId"),
        longValue(row, "jobInstanceId"),
        booleanValue(row, "healthy"),
        InstanceSummary.from(asMap(row.get("instance"))),
        DiagnosisSummary.from(asMap(row.get("summary"))),
        mapList(row.get("findings")).stream().map(Finding::from).toList());
  }
}
