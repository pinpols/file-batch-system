package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.asMap;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.localDateValue;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.stringValue;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * 单实例深度诊断响应（{@code GET /cluster-diagnostic/instances/{id}}）。
 *
 * <p>{@code instance} / {@code summary} 由 service 按具名 record 组装（字段固定，值可 null）→ 不加
 * {@code NON_NULL}。嵌套的分区 / 任务 / outbox 状态计数行来自 Mapper 的具名查询视图，响应使用具名行 record；可空列加
 * {@code NON_NULL}。{@code findings[].evidence} 每条依据 finding 类型携带异构键（instanceStatus /
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
  @Builder
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
      return InstanceSummary.builder()
          .id(longValue(row, "id"))
          .instanceNo(stringValue(row, "instanceNo"))
          .jobCode(stringValue(row, "jobCode"))
          .bizDate(localDateValue(row, "bizDate"))
          .instanceStatus(stringValue(row, "instanceStatus"))
          .queueCode(stringValue(row, "queueCode"))
          .workerGroup(stringValue(row, "workerGroup"))
          .traceId(stringValue(row, "traceId"))
          .startedAt(instantValue(row, "startedAt"))
          .deadlineAt(instantValue(row, "deadlineAt"))
          .now(instantValue(row, "now"))
          .build();
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
      List<Map<String, Object>> partitionCountRows = mapList(row.get("partitionStatusCounts"));
      List<StatusCount> partitionStatusCounts =
          partitionCountRows.stream().map(StatusCount::from).toList();
      List<Map<String, Object>> taskCountRows = mapList(row.get("taskStatusCounts"));
      List<StatusCount> taskStatusCounts = taskCountRows.stream().map(StatusCount::from).toList();
      List<Map<String, Object>> outboxCountRows = mapList(row.get("outboxStatusCounts"));
      List<OutboxStatusCount> outboxStatusCounts =
          outboxCountRows.stream().map(OutboxStatusCount::from).toList();
      Long onlineWorkersForGroup = longValue(row, "onlineWorkersForGroup");
      return new DiagnosisSummary(
          partitionStatusCounts,
          taskStatusCounts,
          outboxStatusCounts,
          onlineWorkersForGroup);
    }
  }

  /** 状态计数响应行；可空状态不输出。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record StatusCount(String status, Long count) {
    static StatusCount from(Map<String, Object> row) {
      return new StatusCount(stringValue(row, "status"), longValue(row, "count"));
    }
  }

  /** outbox 状态计数响应行；可空时间不输出。 */
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
      String severity = stringValue(row, "severity");
      String reasonCode = stringValue(row, "reasonCode");
      String message = stringValue(row, "message");
      Object actions = row.get("suggestedActions");
      List<String> suggestedActions = EmptyChecks.isNull(actions) ? List.of() : (List<String>) actions;
      Object evidenceValue = row.get("evidence");
      Map<String, Object> evidence = asMap(evidenceValue);
      return new Finding(severity, reasonCode, message, suggestedActions, evidence);
    }
  }

  public static ConsoleInstanceDiagnosisResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    String tenantId = stringValue(row, "tenantId");
    Long jobInstanceId = longValue(row, "jobInstanceId");
    boolean healthy = booleanValue(row, "healthy");
    Map<String, Object> instanceRow = asMap(row.get("instance"));
    InstanceSummary instance = InstanceSummary.from(instanceRow);
    Map<String, Object> summaryRow = asMap(row.get("summary"));
    DiagnosisSummary summary = DiagnosisSummary.from(summaryRow);
    List<Map<String, Object>> findingRows = mapList(row.get("findings"));
    List<Finding> findings = findingRows.stream().map(Finding::from).toList();
    return new ConsoleInstanceDiagnosisResponse(
        tenantId, jobInstanceId, healthy, instance, summary, findings);
  }
}
