package io.github.pinpols.batch.orchestrator.application.plan;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 调度计划：{@link SchedulePlanBuilder} 的输出产物，描述本次调度所需的作业元数据、分区列表和 Worker 路由。
 *
 * <p>{@code shardTotal}/{@code shardIndex} 为 Outbox 分片参数，由 OutboxPollScheduler 注入；
 * 其他调用方无需设置，默认值为全量单片（total=1, index=0）。
 */
@Data
public class SchedulePlan {

  private String tenantId;
  private String jobCode;
  private String bizDate;
  private Long jobDefinitionId;
  private Long workflowDefinitionId;
  private String queueCode;
  private String workerGroup;
  private String windowCode;
  private String defaultWorkerType;
  private Integer priority;
  private Integer partitionCount;
  private Long totalExpectedRows;
  private List<PartitionPlan> partitions = new ArrayList<>();
  private WorkerRouteModel defaultWorkerRoute;
  // Outbox 分片参数：由 OutboxPollScheduler 注入，其余调用方无需设置
  private int shardTotal = 1;
  private int shardIndex = 0;

  /** ADR-026 dry-run 演练标记；从父 job_instance.dry_run 透传到本次派发的 partition / task。 */
  private boolean dryRun;

  /**
   * 固化分区计划契约，供持久化 input_snapshot、worker 读取和重放诊断复用。
   *
   * <p>约定：partitionNo 是平台内 1-based 序号；shardIndex 是 worker 侧更常用的 0-based 下标。若本次计划带
   * totalExpectedRows，则按 shardIndex/shardTotal 做半开区间 [rangeStartInclusive, rangeEndExclusive) 均分。
   */
  public void normalizePartitionContract() {
    if (partitions == null || partitions.isEmpty()) {
      return;
    }
    int total = partitions.size();
    for (int i = 0; i < total; i++) {
      PartitionPlan partition = partitions.get(i);
      if (partition.getPartitionNo() == null) {
        partition.setPartitionNo(i + 1);
      }
      partition.setShardIndex(i);
      partition.setShardTotal(total);
      if (totalExpectedRows != null && totalExpectedRows >= 0) {
        long start = totalExpectedRows * i / total;
        long end = totalExpectedRows * (i + 1L) / total;
        partition.setRangeStartInclusive(start);
        partition.setRangeEndExclusive(end);
        partition.setExpectedRows(Math.max(0L, end - start));
      }
    }
  }

  @Data
  public static class PartitionPlan {

    private Integer partitionNo;
    private String partitionKey;
    private String businessKey;
    private WorkerRouteModel workerRoute;
    private String partitionStatus;
    private Integer shardIndex;
    private Integer shardTotal;
    private Long rangeStartInclusive;
    private Long rangeEndExclusive;
    private Long expectedRows;

    /** ADR-046 文件束:本 partition 绑定的源文件 id（异构束内各不同;非束作业为 null）。 */
    private Long sourceFileId;

    /** ADR-046 文件束:本 partition 用的文件模板 code。 */
    private String templateCode;

    /** ADR-046 文件束:目标引用（导入=目标表 / 导出=源查询 / 分发=下游;可空,默认由模板推）。 */
    private String targetRef;
  }
}
