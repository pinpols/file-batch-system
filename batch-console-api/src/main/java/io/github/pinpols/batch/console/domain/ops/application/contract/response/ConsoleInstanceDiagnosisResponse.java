package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
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
      Instant now) {}

  /** 分区 / 任务 / outbox 状态计数；实例未指定工作组时，组内在线 worker 数为 null。 */
  public record DiagnosisSummary(
      List<StatusCount> partitionStatusCounts,
      List<StatusCount> taskStatusCounts,
      List<OutboxStatusCount> outboxStatusCounts,
      Long onlineWorkersForGroup) {}

  /** 状态计数响应行；可空状态不输出。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record StatusCount(String status, Long count) {}

  /** outbox 状态计数响应行；可空时间不输出。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OutboxStatusCount(String status, Long count, Instant oldest, Instant newest) {}

  /** 诊断发现项。{@code evidence} 为真动态负载 → 保留 Map。 */
  public record Finding(
      String severity,
      String reasonCode,
      String message,
      List<String> suggestedActions,
      Map<String, Object> evidence) {}
}
