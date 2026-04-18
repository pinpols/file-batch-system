package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.model.WorkerRouteModel;
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
  private List<PartitionPlan> partitions = new ArrayList<>();
  private WorkerRouteModel defaultWorkerRoute;
  // Outbox 分片参数：由 OutboxPollScheduler 注入，其余调用方无需设置
  private int shardTotal = 1;
  private int shardIndex = 0;

  @Data
  public static class PartitionPlan {

    private Integer partitionNo;
    private String partitionKey;
    private String businessKey;
    private WorkerRouteModel workerRoute;
    private String partitionStatus;
  }
}
