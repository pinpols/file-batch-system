package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import java.time.Instant;
import java.util.List;

/** 处理 Worker 分配、任务租约管理、状态更新及日志追加，从 {@link DefaultTaskExecutionService} 中拆分。 */
public interface TaskAssignmentService {

  JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode);

  boolean renewTaskLease(
      String tenantId, Long taskId, String workerCode, String partitionInvocationId);

  /**
   * 加载任务的 effective config 快照(实时读 job_task / job_instance / job_partition / job_definition)。
   *
   * <p>由 {@code POST /internal/tasks/{taskId}/claim} 在 worker 认领成功后调用,把结果作为 HTTP response body 返回给
   * worker。Worker 优先用本对象的字段,缺字段时 fallback 到 {@code TaskDispatchMessage} 旧字段。
   *
   * <p>设计依据:{@code docs/design/batch-classification-and-gaps.md} §3.4 / §4 P1-2。
   *
   * @return effective config;task 不存在时返回 null
   */
  EffectiveTaskConfig loadEffectiveConfig(String tenantId, Long taskId);

  JobTaskEntity updateTaskStatus(
      String tenantId, Long taskId, String taskStatus, String errorCode, String errorMessage);

  JobExecutionLogEntity appendLog(JobExecutionLogEntity log);

  List<JobExecutionLogEntity> listLogs(String tenantId, Long jobInstanceId, Long jobPartitionId);

  JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt);
}
